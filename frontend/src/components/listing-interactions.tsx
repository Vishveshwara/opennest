"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Phone, Star } from "lucide-react";
import { z } from "zod";
import {
  pageSchema,
  reviewSchema,
  stateSchema,
  revealSchema,
  type Listing,
} from "@/lib/contracts";
import { ApiError, errorMessage } from "@/lib/api";
import { useSession } from "./session";
import { Button } from "./ui/button";
import { ConfirmDialog } from "./ui/dialog";
import { Notice } from "./common";

type ListingIdentity = Pick<Listing, "id" | "ownerId">;
type InteractionState = z.infer<typeof stateSchema>;

const ReviewEligibility = createContext<{
  revision: number;
  refresh: () => void;
}>({ revision: 0, refresh: () => {} });

export function ListingInteractionScope({
  id,
  children,
}: {
  id: string;
  children: ReactNode;
}) {
  const { epoch } = useSession();
  return <InteractionScope key={`${id}-${epoch}`}>{children}</InteractionScope>;
}

function InteractionScope({ children }: { children: ReactNode }) {
  const [revision, setRevision] = useState(0);
  return (
    <ReviewEligibility.Provider
      value={{ revision, refresh: () => setRevision((value) => value + 1) }}
    >
      <div className="detail-columns">{children}</div>
    </ReviewEligibility.Provider>
  );
}

function quotaEndsAt(date: string) {
  return Date.parse(`${date}T00:00:00+05:30`) + 86_400_000;
}

export function ListingContact({ listing }: { listing: ListingIdentity }) {
  const { api, account, config } = useSession();
  const { refresh: refreshEligibility } = useContext(ReviewEligibility);
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<
    { attempt: number } & ({ state: InteractionState } | { error: string })
  >();
  const current = settled?.attempt === attempt ? settled : undefined;
  const state = current && "state" in current ? current.state : undefined;
  const readError = current && "error" in current ? current.error : undefined;
  const [contact, setContact] = useState<z.infer<typeof revealSchema> | null>(
    null,
  );
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const canReveal = Boolean(
    account && account.id !== listing.ownerId && config.mode !== "SANDBOX",
  );
  useEffect(() => {
    if (!canReveal) return;
    let alive = true;
    void api
      .request(`/api/v1/interactions/listings/${listing.id}/state`, stateSchema)
      .then((state) => {
        if (alive) setSettled({ attempt, state });
      })
      .catch((error) => {
        if (alive) setSettled({ attempt, error: errorMessage(error) });
      });
    return () => {
      alive = false;
    };
  }, [api, canReveal, listing.id, attempt]);

  const quotaDate = state?.quotaDate;
  useEffect(() => {
    if (!quotaDate || contact) return;
    const expires = quotaEndsAt(quotaDate);
    const refreshExpired = () => {
      if (Date.now() >= expires) setAttempt((value) => value + 1);
    };
    const remaining = expires - Date.now();
    const timer =
      remaining > 0 ? window.setTimeout(refreshExpired, remaining) : undefined;
    window.addEventListener("focus", refreshExpired);
    document.addEventListener("visibilitychange", refreshExpired);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", refreshExpired);
      document.removeEventListener("visibilitychange", refreshExpired);
    };
  }, [quotaDate, contact]);

  async function reveal() {
    if (!state || busy) return;
    if (Date.now() >= quotaEndsAt(state.quotaDate)) {
      setAttempt((value) => value + 1);
      return;
    }
    setBusy(true);
    setError("");
    try {
      const revealed = await api.request(
        `/api/v1/interactions/listings/${listing.id}/reveal-phone`,
        revealSchema,
        "POST",
        {},
      );
      setContact(revealed);
      refreshEligibility();
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }
  if (!account)
    return (
      <Button asChild>
        <Link
          href={`${config.basePath}${config.mode === "SANDBOX" ? "/personas" : "/login"}?returnTo=${encodeURIComponent(`${config.basePath}/listings/${listing.id}`)}`}
        >
          Sign in to connect
        </Link>
      </Button>
    );
  if (account.id === listing.ownerId)
    return (
      <Button asChild variant="outline">
        <Link href={`${config.basePath}/my-listings/${listing.id}`}>
          Manage this listing
        </Link>
      </Button>
    );
  return (
    <div className="stack">
      {error && <Notice error>{error}</Notice>}
      {config.mode === "SANDBOX" || state?.contactActionsEnabled === false ? (
        <Notice>Contact actions are disabled in the recruiter sandbox.</Notice>
      ) : contact ? (
        <div className="revealed-contact">
          <p>Your authorized owner contact</p>
          <a href={`tel:${contact.phoneNumber}`}>
            <Phone size={16} />
            {contact.phoneNumber}
          </a>
          <a
            className="text-link"
            href={contact.whatsappUrl}
            target="_blank"
            rel="noreferrer"
          >
            Open WhatsApp
          </a>
          <p className="fine-print">
            {contact.remainingReveals} distinct contacts remaining on{" "}
            {contact.revealedDate}, Asia/Kolkata.
          </p>
        </div>
      ) : (
        <>
          {readError && (
            <>
              <Notice error>{readError}</Notice>
              <Button
                type="button"
                variant="outline"
                onClick={() => setAttempt((value) => value + 1)}
              >
                Retry contact status
              </Button>
            </>
          )}
          <Button
            disabled={
              busy ||
              !state ||
              (!state.revealedToday && state.remainingReveals === 0)
            }
            onClick={() => void reveal()}
          >
            <Phone size={17} />
            {busy
              ? "Connecting…"
              : state?.revealedToday
                ? "Show owner contact again"
                : "Reveal owner contact"}
          </Button>
          {state && (
            <p className="fine-print">
              {state.remainingReveals} of 10 distinct contacts remaining on{" "}
              {state.quotaDate}, Asia/Kolkata. Same-day repeat reveals are free.
            </p>
          )}
        </>
      )}
    </div>
  );
}

const reviewPageSchema = pageSchema(reviewSchema);
type ReviewPage = z.infer<typeof reviewPageSchema>;
type PageRequest = { page: number };

export function ListingReviews({ listing }: { listing: ListingIdentity }) {
  const { api, account } = useSession();
  const { revision, refresh: refreshEligibility } =
    useContext(ReviewEligibility);
  const router = useRouter();
  const [request, setRequest] = useState<PageRequest>({ page: 0 });
  const [loaded, setLoaded] = useState<{
    request: PageRequest;
    reviews?: ReviewPage;
    error?: string;
  }>();
  const reviews = loaded?.reviews;
  const loadingPage = loaded?.request !== request;
  const [state, setState] = useState<InteractionState | null>(null);
  const [eligibilityError, setEligibilityError] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    void api
      .request(
        `/api/v1/interactions/listings/${listing.id}/reviews?page=${request.page}&size=5`,
        reviewPageSchema,
      )
      .then((reviews) => {
        if (active) setLoaded({ request, reviews });
      })
      .catch((error) => {
        if (active)
          setLoaded((previous) => ({
            request,
            reviews: previous?.reviews,
            error: errorMessage(error),
          }));
      });
    return () => {
      active = false;
    };
  }, [api, listing.id, request]);

  useEffect(() => {
    if (!account) return;
    let active = true;
    void api
      .request(`/api/v1/interactions/listings/${listing.id}/state`, stateSchema)
      .then((value) => {
        if (active) {
          setState(value);
          setEligibilityError("");
        }
      })
      .catch((error) => {
        if (active && !(error instanceof ApiError && error.status === 401))
          setEligibilityError(errorMessage(error));
      });
    return () => {
      active = false;
    };
  }, [api, account, listing.id, revision]);

  async function write(body: unknown, report = false) {
    setBusy(true);
    setError("");
    try {
      await api.request(
        `/api/v1/interactions/listings/${listing.id}/${report ? "report-brokerage" : "reviews"}`,
        reviewSchema,
        "POST",
        body,
      );
      setRequest({ page: reviews?.page ?? 0 });
      refreshEligibility();
      router.refresh();
    } catch (error) {
      setError(errorMessage(error));
      if (error instanceof ApiError && error.status === 409) {
        setRequest({ page: reviews?.page ?? 0 });
        refreshEligibility();
      }
    } finally {
      setBusy(false);
    }
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void write({
      rating: Number(data.get("rating")),
      issueTag: data.get("issueTag"),
      commentText: String(data.get("commentText") ?? "").trim() || null,
    });
  }
  return (
    <section className="detail-section">
      <h2>From the community</h2>
      <p>
        Experiences shared by people who previously revealed this listing’s
        contact.
      </p>
      {(error || eligibilityError || (!loadingPage && loaded?.error)) && (
        <Notice error>{error || eligibilityError || loaded?.error}</Notice>
      )}
      {reviews?.items.map((review) => (
        <article className="review" key={review.id}>
          <div className="review-header">
            <span>
              <Star size={15} /> {review.rating} / 5
            </span>
            <time dateTime={review.createdAt}>
              {new Date(review.createdAt).toLocaleDateString("en-IN", {
                timeZone: "Asia/Kolkata",
              })}
            </time>
          </div>
          {review.allegationNotice && (
            <Notice>{review.allegationNotice}</Notice>
          )}
          <p className="fine-print">{review.issueTag.replaceAll("_", " ")}</p>
          {review.commentText && (
            <p className="description">{review.commentText}</p>
          )}
        </article>
      ))}
      {reviews?.items.length === 0 && (
        <p>No reviews yet. Owners without reviews are unrated.</p>
      )}
      {reviews && (
        <nav
          className="actions"
          aria-label="Review pagination"
          aria-busy={loadingPage}
        >
          <Button
            variant="outline"
            size="small"
            disabled={loadingPage || !reviews.hasPrevious}
            onClick={() => setRequest({ page: reviews.page - 1 })}
          >
            Previous reviews
          </Button>
          <span>
            Page {reviews.totalPages === 0 ? 0 : reviews.page + 1} of{" "}
            {reviews.totalPages}
          </span>
          <Button
            variant="outline"
            size="small"
            disabled={loadingPage || !reviews.hasNext}
            onClick={() => setRequest({ page: reviews.page + 1 })}
          >
            Next reviews
          </Button>
        </nav>
      )}
      {state?.review && (
        <p className="fine-print">
          Your review is saved. Ordinary reviews cannot be edited or used to
          clear a report.
        </p>
      )}
      {state?.canReview && (
        <form onSubmit={submit} className="panel stack review-form">
          <h3>Share your experience</h3>
          <label className="field">
            Rating
            <select name="rating" defaultValue="5">
              {[5, 4, 3, 2, 1].map((value) => (
                <option key={value} value={value}>
                  {value} / 5
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Experience
            <select name="issueTag">
              <option value="GENUINE_OWNER">Genuine owner</option>
              <option value="MAINTENANCE_ISSUE">Maintenance issue</option>
              <option value="FAKE_PHOTOS">Photos did not match</option>
              <option value="OTHER">Other</option>
            </select>
          </label>
          <label className="field">
            Comment, optional
            <textarea name="commentText" maxLength={2000} rows={4} />
          </label>
          <Button disabled={busy} type="submit">
            Submit review
          </Button>
        </form>
      )}
      {state?.canReportBrokerage && (
        <div className="report-area">
          <p>
            Were you asked to pay brokerage? A report creates or converts your
            review to one star and records an allegation.
          </p>
          <ConfirmDialog
            title="Report a brokerage request?"
            description="This records an allegation and changes your review to one star. Existing comment text is preserved. The listing will remain visible."
            trigger="Report brokerage request"
            busy={busy}
            confirm={() => write({}, true)}
          />
        </div>
      )}
      {account &&
        state &&
        !state.hasHistoricalReveal &&
        account.id !== listing.ownerId && (
          <p className="fine-print">
            Reveal this listing’s contact before sharing a review or report.
          </p>
        )}
    </section>
  );
}

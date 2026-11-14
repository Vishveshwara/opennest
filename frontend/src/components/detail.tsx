"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  MapPin,
  Phone,
  Star,
  Wifi,
  Dumbbell,
  Waves,
  ArrowLeft,
} from "lucide-react";
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
import {
  Notice,
  TrustBadge,
  capacityLabel,
  imageUrl,
  modeLabel,
  rentLabel,
} from "./common";
import AreaMap from "./area-map";

export function Detail({ listing }: { listing: Listing }) {
  const { config, epoch } = useSession();
  return (
    <div className="container section">
      <Link href={`${config.basePath}/`} className="back-link">
        <ArrowLeft size={15} /> Back to places
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">
            {modeLabel(listing)} ·{" "}
            {listing.status === "ACTIVE" ? "Available" : "Fully occupied"}
          </p>
          <h1>{listing.title}</h1>
          <p className="location">
            <MapPin size={17} />
            {listing.locality}, {listing.city}
          </p>
        </div>
        <TrustBadge value={listing.owner.trustBadge} />
      </div>
      <div className="photo-gallery">
        {listing.images
          .filter((image) => image.url)
          .map((image, index) => (
            <img
              key={image.imageId}
              src={imageUrl(image.url ?? "", config.apiOrigin)}
              alt={`${listing.title}, ${image.segment.toLowerCase().replaceAll("_", " ")} photo ${index + 1}`}
              loading={index === 0 ? "eager" : "lazy"}
              referrerPolicy="no-referrer"
            />
          ))}
        {listing.images.length === 0 && (
          <div className="listing-placeholder">No photos available</div>
        )}
      </div>
      <div className="detail-columns">
        <div>
          <section className="detail-section">
            <h2>A little about this place</h2>
            <div className="detail-pills">
              <span className="badge">{modeLabel(listing)}</span>
              {listing.bedrooms !== null && (
                <span className="badge">
                  {listing.bedrooms === 0
                    ? "Studio"
                    : `${listing.bedrooms} BHK flat layout`}
                </span>
              )}
              <span className="badge">{capacityLabel(listing)}</span>
            </div>
            <p className="description">{listing.description}</p>
            <p>
              {listing.flatRentalMode === "ENTIRE_FLAT"
                ? listing.wholeFlatAvailable
                  ? "The whole flat is available."
                  : "The whole flat is occupied."
                : `${listing.totalRoomsAvailable} rooms available: ${listing.maleRoomsAvailable} male, ${listing.femaleRoomsAvailable} female, ${listing.sharedRoomsAvailable} shared.`}
            </p>
            <div className="amenities">
              {listing.hasWifi && (
                <span>
                  <Wifi size={18} />
                  Wi-Fi
                </span>
              )}
              {listing.hasGym && (
                <span>
                  <Dumbbell size={18} />
                  Gym
                </span>
              )}
              {listing.hasSwimmingPool && (
                <span>
                  <Waves size={18} />
                  Swimming pool
                </span>
              )}
            </div>
          </section>
          <section className="detail-section">
            <h2>The neighborhood</h2>
            <p>
              {listing.locality}, {listing.city}. The map shows an approximate
              area.
            </p>
            <AreaMap listings={[listing]} />
          </section>
          <Reviews key={`${listing.id}-${epoch}`} listing={listing} />
        </div>
        <aside className="detail-aside">
          <div className="panel contact-panel">
            <p className="eyebrow">Your next place</p>
            <h2 className="price-detail">{rentLabel(listing)}</h2>
            <p>
              Security deposit: INR{" "}
              {listing.securityDeposit.toLocaleString("en-IN")} /{" "}
              {listing.rentBasis === "PER_PERSON"
                ? "person"
                : listing.rentBasis === "PER_PROPERTY"
                  ? "property"
                  : "room"}
            </p>
            <hr />
            <p className="owner-name">Listed by {listing.owner.fullName}</p>
            <p>
              <Star size={14} className="inline-icon" />{" "}
              {listing.owner.averageRating === null
                ? "Not yet rated"
                : `${listing.owner.averageRating.toFixed(1)} / 5`}{" "}
              · {listing.owner.reviewCount} reviews
            </p>
            <TrustBadge value={listing.owner.trustBadge} />
            <p className="fine-print">
              Community reports describe allegations, not proven misconduct.
            </p>
            <Contact key={`${listing.id}-${epoch}`} listing={listing} />
          </div>
        </aside>
      </div>
    </div>
  );
}

function Contact({ listing }: { listing: Listing }) {
  const { api, account, config } = useSession();
  const [state, setState] = useState<z.infer<typeof stateSchema> | null>(null),
    [contact, setContact] = useState<z.infer<typeof revealSchema> | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!account) return;
    let alive = true;
    void api
      .request(`/api/v1/interactions/listings/${listing.id}/state`, stateSchema)
      .then((value) => {
        if (alive) setState(value);
      })
      .catch((error) => {
        if (alive) setError(errorMessage(error));
      });
    return () => {
      alive = false;
    };
  }, [api, account, listing.id]);
  async function reveal() {
    setBusy(true);
    setError("");
    try {
      setContact(
        await api.request(
          `/api/v1/interactions/listings/${listing.id}/reveal-phone`,
          revealSchema,
          "POST",
          {},
        ),
      );
      setState(
        await api.request(
          `/api/v1/interactions/listings/${listing.id}/state`,
          stateSchema,
        ),
      );
      window.dispatchEvent(new Event("opennest:interaction"));
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

function Reviews({ listing }: { listing: Listing }) {
  const { api, account } = useSession();
  const router = useRouter();
  const [page, setPage] = useState(0),
    [reviews, setReviews] = useState<z.infer<
      ReturnType<typeof pageSchema<typeof reviewSchema>>
    > | null>(null),
    [state, setState] = useState<z.infer<typeof stateSchema> | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [reload, setReload] = useState(0);
  useEffect(() => {
    const listener = () => setReload((value) => value + 1);
    window.addEventListener("opennest:interaction", listener);
    return () => window.removeEventListener("opennest:interaction", listener);
  }, []);
  useEffect(() => {
    let active = true;
    void api
      .request(
        `/api/v1/interactions/listings/${listing.id}/reviews?page=${page}&size=5`,
        pageSchema(reviewSchema),
      )
      .then((value) => {
        if (active) setReviews(value);
      })
      .catch((error) => {
        if (active) setError(errorMessage(error));
      });
    if (account)
      void api
        .request(
          `/api/v1/interactions/listings/${listing.id}/state`,
          stateSchema,
        )
        .then((value) => {
          if (active) setState(value);
        })
        .catch((error) => {
          if (active && !(error instanceof ApiError && error.status === 401))
            setError(errorMessage(error));
        });
    return () => {
      active = false;
    };
  }, [api, listing.id, page, account, reload]);
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
      setReload((value) => value + 1);
      router.refresh();
    } catch (error) {
      setError(errorMessage(error));
      if (error instanceof ApiError && error.status === 409)
        setReload((value) => value + 1);
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
      {error && <Notice error>{error}</Notice>}
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
        <nav className="actions" aria-label="Review pagination">
          <Button
            variant="outline"
            size="small"
            disabled={!reviews.hasPrevious}
            onClick={() => setPage((value) => value - 1)}
          >
            Previous reviews
          </Button>
          <span>
            Page {reviews.totalPages === 0 ? 0 : page + 1} of{" "}
            {reviews.totalPages}
          </span>
          <Button
            variant="outline"
            size="small"
            disabled={!reviews.hasNext}
            onClick={() => setPage((value) => value + 1)}
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

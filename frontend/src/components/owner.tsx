"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { z } from "zod";
import {
  ArrowLeft,
  Plus,
  ImagePlus,
  Check,
  Upload,
  Pause,
  Play,
} from "lucide-react";
import {
  ownerListingSchema,
  pageSchema,
  segmentSchema,
  presignSchema,
  imageSchema,
  uuid,
  type OwnerListing,
  type ImageSegment,
} from "@/lib/contracts";
import { ApiError, errorMessage } from "@/lib/api";
import {
  convertToLosslessWebp,
  uploadDirect,
  UploadTransportError,
} from "@/lib/uploads";
import { useSession } from "./session";
import { AuthGate } from "./auth";
import { Button } from "./ui/button";
import {
  Notice,
  Pagination,
  modeLabel,
  rentLabel,
  capacityLabel,
  imageUrl,
} from "./common";
import AreaMap from "./area-map";
import { MAX_LISTING_PHOTOS } from "@/lib/photo-policy";

export function OwnerPage({ id }: { id?: string }) {
  const { epoch } = useSession();
  return (
    <div className="container section">
      <AuthGate>
        <OwnerContent key={epoch} id={id} />
      </AuthGate>
    </div>
  );
}

function OwnerContent({ id }: { id?: string }) {
  const { config } = useSession();
  if (id === "new") return <CreateDraft />;
  if (id) return <ManageListing id={id} />;
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Make room for someone new</p>
          <h1>My listings</h1>
        </div>
        <Button asChild>
          <Link href={`${config.basePath}/my-listings/new`}>
            <Plus size={17} /> Create a draft
          </Link>
        </Button>
      </div>
      <p>
        Drafts stay private until you explicitly publish. Up to five active or
        fully occupied listings count toward your publication allowance.
      </p>
      <OwnerCollection />
    </>
  );
}

function OwnerCollection() {
  const { api, config } = useSession();
  const router = useRouter();
  const params = useSearchParams();
  const [result, setResult] = useState<z.infer<
      ReturnType<typeof pageSchema<typeof ownerListingSchema>>
    > | null>(null),
    [error, setError] = useState("");
  const query = params.toString();
  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const status = data.get("status");
    const query = new URLSearchParams();
    if (typeof status === "string" && status) query.set("status", status);
    router.push(`${config.basePath}/my-listings?${query}`);
  }
  useEffect(() => {
    let active = true;
    void api
      .request(`/api/v1/listings/mine?${query}`, pageSchema(ownerListingSchema))
      .then((value) => {
        if (active) {
          setResult(value);
          setError("");
        }
      })
      .catch((error) => {
        if (active) setError(errorMessage(error));
      });
    return () => {
      active = false;
    };
  }, [api, query]);
  const href = (page: number) => {
    const query = new URLSearchParams(params);
    query.set("page", String(page));
    return `${config.basePath}/my-listings?${query}`;
  };
  return (
    <>
      {error && <Notice error>{error}</Notice>}
      <form className="actions owner-filters" onSubmit={filter}>
        <label className="field">
          Status
          <select name="status" defaultValue={params.get("status") ?? ""}>
            <option value="">All statuses</option>
            <option value="PAUSED">Paused / draft</option>
            <option value="ACTIVE">Active</option>
            <option value="FULL_OCCUPIED">Fully occupied</option>
          </select>
        </label>
        <Button variant="outline">Apply</Button>
      </form>
      {!result && !error && <p role="status">Loading your listings…</p>}
      {result?.items.length === 0 && (
        <div className="empty-state">
          <h2>A place to begin.</h2>
          <p>Create a draft, add your photos, then publish when it is ready.</p>
          <Link
            className="text-link"
            href={`${config.basePath}/my-listings/new`}
          >
            Create a draft
          </Link>
        </div>
      )}
      <div className="owner-list">
        {result?.items.map((listing) => (
          <article className="panel owner-row" key={listing.id}>
            <div>
              <span className="badge">
                {listing.status.replaceAll("_", " ")}
              </span>
              <h2>
                <Link href={`${config.basePath}/my-listings/${listing.id}`}>
                  {listing.title}
                </Link>
              </h2>
              <p>
                {listing.locality}, {listing.city} · {modeLabel(listing)}
              </p>
              <p>{rentLabel(listing)}</p>
            </div>
            <Button asChild variant="outline">
              <Link href={`${config.basePath}/my-listings/${listing.id}`}>
                Manage listing
              </Link>
            </Button>
          </article>
        ))}
      </div>
      {result && <Pagination {...result} href={href} />}
    </>
  );
}

const int = (data: FormData, key: string) =>
  z.coerce.number().int().min(0).max(2147483647).parse(data.get(key));
const text = (data: FormData, key: string) =>
  z.string().trim().min(1).parse(data.get(key));
function InventoryFields({
  entire,
  listing,
}: {
  entire: boolean;
  listing?: OwnerListing;
}) {
  return entire ? (
    <>
      <label className="field">
        Whole-flat availability
        <select
          name="wholeFlatAvailable"
          defaultValue={String(listing?.wholeFlatAvailable ?? true)}
        >
          <option value="true">Available</option>
          <option value="false">Fully occupied</option>
        </select>
      </label>
      <label className="field">
        Maximum occupants for the entire flat
        <input
          name="maxOccupants"
          type="number"
          min={1}
          step={1}
          max={2147483647}
          required
          defaultValue={listing?.maxOccupants ?? 1}
        />
      </label>
    </>
  ) : (
    <>
      {[
        [
          "Male rooms available",
          "maleRoomsAvailable",
          listing?.maleRoomsAvailable,
        ],
        [
          "Female rooms available",
          "femaleRoomsAvailable",
          listing?.femaleRoomsAvailable,
        ],
        [
          "Shared rooms available",
          "sharedRoomsAvailable",
          listing?.sharedRoomsAvailable,
        ],
      ].map(([label, name, value]) => (
        <label className="field" key={String(name)}>
          {String(label)}
          <input
            name={String(name)}
            type="number"
            min={0}
            step={1}
            max={2147483647}
            required
            defaultValue={Number(value ?? 0)}
          />
        </label>
      ))}
      <label className="field">
        Maximum occupants per room
        <input
          name="maxSharingPerRoom"
          type="number"
          min={1}
          max={10}
          step={1}
          required
          defaultValue={listing?.maxSharingPerRoom ?? 1}
        />
      </label>
    </>
  );
}

function inventory(data: FormData, entire: boolean) {
  return entire
    ? {
        wholeFlatAvailable: data.get("wholeFlatAvailable") === "true",
        maxOccupants: int(data, "maxOccupants"),
      }
    : {
        maleRoomsAvailable: int(data, "maleRoomsAvailable"),
        femaleRoomsAvailable: int(data, "femaleRoomsAvailable"),
        sharedRoomsAvailable: int(data, "sharedRoomsAvailable"),
        maxSharingPerRoom: int(data, "maxSharingPerRoom"),
      };
}

function CreateDraft() {
  const { api, config } = useSession();
  const router = useRouter();
  const [category, setCategory] = useState("FLAT"),
    [mode, setMode] = useState("ENTIRE_FLAT"),
    [location, setLocation] = useState({ latitude: "", longitude: "" }),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const entire = category === "FLAT" && mode === "ENTIRE_FLAT";
  const setPoint = useCallback(
    (point: { latitude: number; longitude: number }) =>
      setLocation({
        latitude: String(point.latitude),
        longitude: String(point.longitude),
      }),
    [],
  );
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const data = new FormData(event.currentTarget);
      const body = {
        title: text(data, "title"),
        description: text(data, "description"),
        category,
        ...(category === "FLAT"
          ? { flatRentalMode: mode, bedrooms: int(data, "bedrooms") }
          : {}),
        city: text(data, "city"),
        locality: text(data, "locality"),
        privateAddress: text(data, "privateAddress"),
        latitude: Number(location.latitude),
        longitude: Number(location.longitude),
        monthlyRent: int(data, "monthlyRent"),
        securityDeposit: int(data, "securityDeposit"),
        hasWifi: data.has("hasWifi"),
        hasGym: data.has("hasGym"),
        hasSwimmingPool: data.has("hasSwimmingPool"),
        ...inventory(data, entire),
      };
      const value = await api.request(
        "/api/v1/listings",
        ownerListingSchema,
        "POST",
        body,
      );
      router.replace(`${config.basePath}/my-listings/${value.id}`);
      router.refresh();
    } catch (error) {
      setError(
        error instanceof z.ZodError
          ? "Check the required fields and whole-number amounts."
          : errorMessage(error),
      );
    } finally {
      setBusy(false);
    }
  }
  const basis =
    category === "PG_HOSTEL" ? "person" : entire ? "property" : "room";
  return (
    <>
      <Link href={`${config.basePath}/my-listings`} className="back-link">
        <ArrowLeft size={15} /> My listings
      </Link>
      <p className="eyebrow">First, the essentials</p>
      <h1>Create a listing draft</h1>
      <p>
        Save a private draft first. Add and validate the required photos, then
        choose Publish.
      </p>
      {error && <Notice error>{error}</Notice>}
      <form onSubmit={submit} className="listing-form">
        <section className="panel">
          <h2>Tell us about your place</h2>
          <div className="form-grid">
            <label className="field">
              Listing title
              <input name="title" maxLength={160} required />
            </label>
            <label className="field">
              Category
              <select
                value={category}
                onChange={(event) => setCategory(event.target.value)}
              >
                <option value="FLAT">Flat</option>
                <option value="PG_HOSTEL">PG / Hostel</option>
              </select>
            </label>
            {category === "FLAT" && (
              <>
                <label className="field">
                  Rental mode
                  <select
                    value={mode}
                    onChange={(event) => setMode(event.target.value)}
                  >
                    <option value="ENTIRE_FLAT">Entire flat</option>
                    <option value="ROOM">Room in a flat</option>
                  </select>
                </label>
                <label className="field">
                  Bedrooms / BHK in the containing flat
                  <input
                    name="bedrooms"
                    type="number"
                    min={0}
                    max={2147483647}
                    step={1}
                    required
                    defaultValue={1}
                  />
                  <small>0 means studio. This is layout, not vacancy.</small>
                </label>
              </>
            )}
            <label className="field field-wide">
              Description
              <textarea name="description" maxLength={5000} rows={5} required />
            </label>
          </div>
          <p className="fine-print">
            Category and rental mode cannot be changed after creation.
          </p>
        </section>
        <section className="panel">
          <h2>Location</h2>
          <p>
            Exact address and coordinates stay owner-only. Public maps show an
            approximate area.
          </p>
          <div className="form-grid">
            {[
              ["City", "city", 120],
              ["Locality", "locality", 120],
              ["Private street address", "privateAddress", 500],
            ].map(([label, name, max]) => (
              <label className="field" key={name}>
                {label}
                <input name={String(name)} maxLength={Number(max)} required />
              </label>
            ))}
            {(
              [
                ["Latitude", "latitude", 6.5, 37.5],
                ["Longitude", "longitude", 68, 97.5],
              ] satisfies [string, keyof typeof location, number, number][]
            ).map(([label, name, min, max]) => (
              <label className="field" key={name}>
                {label}
                <input
                  name={name}
                  type="number"
                  min={min}
                  max={max}
                  step="0.000001"
                  required
                  value={location[name]}
                  onChange={(event) =>
                    setLocation({ ...location, [name]: event.target.value })
                  }
                />
              </label>
            ))}
          </div>
          <AreaMap
            onPoint={setPoint}
            point={
              location.latitude && location.longitude
                ? {
                    latitude: Number(location.latitude),
                    longitude: Number(location.longitude),
                  }
                : undefined
            }
          />
          <p className="fine-print">
            Any Indian city is supported. The coordinate bounds are coarse
            validation, not an India boundary check.
          </p>
        </section>
        <section className="panel">
          <h2>Price and availability</h2>
          <div className="form-grid">
            <label className="field">
              Monthly rent, INR / {basis} / month
              <input
                name="monthlyRent"
                type="number"
                min={1}
                max={2147483647}
                step={1}
                required
              />
            </label>
            <label className="field">
              Security deposit, INR / {basis}
              <input
                name="securityDeposit"
                type="number"
                min={0}
                max={2147483647}
                step={1}
                required
                defaultValue={0}
              />
            </label>
            <InventoryFields entire={entire} />
          </div>
          <p className="fine-print">
            Capacity never multiplies the quoted rent. Room counts measure
            rooms, not beds or people.
          </p>
          <div className="actions">
            {[
              ["Wi-Fi", "hasWifi"],
              ["Gym", "hasGym"],
              ["Swimming pool", "hasSwimmingPool"],
            ].map(([label, name]) => (
              <label className="checkbox" key={name}>
                <input type="checkbox" name={name} />
                {label}
              </label>
            ))}
          </div>
        </section>
        <Button type="submit" disabled={busy}>
          {busy ? "Saving draft…" : "Save private draft"}
        </Button>
      </form>
    </>
  );
}

function ManageListing({ id }: { id: string }) {
  const { api, account, config } = useSession();
  const router = useRouter();
  const [listing, setListing] = useState<OwnerListing | null>(null),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    if (!uuid.safeParse(id).success) throw new ApiError(404);
    const value = await api.request(
      `/api/v1/listings/${id}`,
      ownerListingSchema,
    );
    if (value.ownerId !== account?.id) throw new ApiError(403);
    setListing(value);
  }, [api, id, account?.id]);
  useEffect(() => {
    let active = true;
    if (!uuid.safeParse(id).success) return;
    void api
      .request(`/api/v1/listings/${id}`, ownerListingSchema)
      .then((value) => {
        if (!active) return;
        if (value.ownerId !== account?.id) throw new ApiError(403);
        setListing(value);
        setError("");
      })
      .catch((error) => {
        if (active) setError(errorMessage(error));
      });
    return () => {
      active = false;
    };
  }, [api, id, account?.id]);
  async function mutate(
    action: "inventory" | "publish" | "pause",
    body: unknown,
  ) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      setListing(
        await api.request(
          `/api/v1/listings/${id}/${action}`,
          ownerListingSchema,
          action === "inventory" ? "PATCH" : "POST",
          body,
        ),
      );
      setMessage(
        action === "inventory"
          ? "Availability saved."
          : action === "pause"
            ? "Listing paused."
            : "Listing published.",
      );
      router.refresh();
    } catch (error) {
      setError(errorMessage(error));
      if (error instanceof ApiError && error.status === 409) {
        await load().catch(() => {});
        setError(
          "The listing changed or a publication requirement was not met. Current data has been reloaded. Review it before submitting again.",
        );
      }
    } finally {
      setBusy(false);
    }
  }
  if (!listing)
    return (
      <>
        {error ? (
          <Notice error>{error}</Notice>
        ) : (
          <p role="status">Loading your listing…</p>
        )}
      </>
    );
  const required: ImageSegment[] =
    listing.category === "FLAT"
      ? ["ROOM", "HALL", "BATHROOM", "KITCHEN"]
      : ["ROOM", "BATHROOM"];
  const missing = required.filter(
    (segment) => !listing.images.some((image) => image.segment === segment),
  );
  const submitInventory = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    try {
      void mutate("inventory", {
        ...inventory(
          new FormData(event.currentTarget),
          listing.flatRentalMode === "ENTIRE_FLAT",
        ),
        version: listing.version,
      });
    } catch {
      setError("Use valid whole-number room counts and capacity.");
    }
  };
  return (
    <>
      <Link href={`${config.basePath}/my-listings`} className="back-link">
        <ArrowLeft size={15} /> My listings
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">
            {listing.status.replaceAll("_", " ")} · {modeLabel(listing)}
          </p>
          <h1>{listing.title}</h1>
          <p>
            {rentLabel(listing)} · {capacityLabel(listing)}
          </p>
        </div>
        {listing.status !== "PAUSED" && (
          <Button asChild variant="outline">
            <Link href={`${config.basePath}/listings/${id}`}>
              View public listing
            </Link>
          </Button>
        )}
      </div>
      {error && <Notice error>{error}</Notice>}
      {message && <Notice>{message}</Notice>}
      <div className="owner-management">
        <div>
          <section className="panel">
            <h2>Availability and capacity</h2>
            <form
              key={listing.version}
              onSubmit={submitInventory}
              className="stack"
            >
              <div className="form-grid">
                <InventoryFields
                  entire={listing.flatRentalMode === "ENTIRE_FLAT"}
                  listing={listing}
                />
              </div>
              <p className="fine-print">
                Updating a paused listing does not publish it. BHK, prices, and
                listing text are fixed in this version.
              </p>
              <Button type="submit" disabled={busy}>
                Save availability
              </Button>
            </form>
          </section>
          <PhotoUploader listing={listing} reload={load} />
          <section className="panel">
            <h2>Your private location</h2>
            <p>{listing.privateAddress}</p>
            <p className="fine-print">
              {listing.latitude}, {listing.longitude}. Only you can access these
              exact values.
            </p>
          </section>
        </div>
        <aside>
          <section className="panel publication-panel">
            <h2>Publication checklist</h2>
            <ul className="checklist">
              <li>
                {account?.phoneVerified ? <Check size={17} /> : <span>○</span>}
                Verified +91 contact
              </li>
              {required.map((segment) => (
                <li key={segment}>
                  {missing.includes(segment) ? (
                    <span>○</span>
                  ) : (
                    <Check size={17} />
                  )}
                  {segment.toLowerCase().replaceAll("_", " ")} photo validated
                </li>
              ))}
            </ul>
            <p className="fine-print">
              Publication also checks current inventory, ownership, and the
              five-listing quota on the server. Up to 20 retained listings are
              allowed.
            </p>
            {listing.status === "PAUSED" ? (
              <Button
                disabled={busy || !account?.phoneVerified || missing.length > 0}
                onClick={() =>
                  void mutate("publish", { version: listing.version })
                }
              >
                <Play size={16} />
                Publish listing
              </Button>
            ) : (
              <Button
                variant="outline"
                disabled={busy}
                onClick={() =>
                  void mutate("pause", { version: listing.version })
                }
              >
                <Pause size={16} />
                Pause listing
              </Button>
            )}
          </section>
        </aside>
      </div>
    </>
  );
}

type UploadJob = {
  key: string;
  file: File;
  segment: ImageSegment;
  progress: number;
  status:
    | "ready"
    | "compressing"
    | "uploading"
    | "validating"
    | "complete"
    | "error";
  error: string;
  imageId?: string;
  uploaded?: boolean;
  reservation?: { ticket: z.infer<typeof presignSchema>; blob: Blob };
};
function PhotoUploader({
  listing,
  reload,
}: {
  listing: OwnerListing;
  reload: () => Promise<void>;
}) {
  const { api, config } = useSession();
  const [segment, setSegment] = useState<ImageSegment>("ROOM"),
    [jobs, setJobs] = useState<UploadJob[]>([]),
    [busy, setBusy] = useState(false);
  const update = (key: string, changes: Partial<UploadJob>) =>
    setJobs((current) =>
      current.map((job) => (job.key === key ? { ...job, ...changes } : job)),
    );
  async function run(job: UploadJob, now: number) {
    setBusy(true);
    let imageId = job.imageId,
      uploaded = job.uploaded;
    let reservation = job.reservation;
    try {
      if (!uploaded) {
        update(job.key, { status: "compressing", error: "", progress: 0 });
        const blob =
          reservation?.blob ?? (await convertToLosslessWebp(job.file));
        if (!reservation || now >= Date.parse(reservation.ticket.expiresAt)) {
          const ticket = await api.request(
            `/api/v1/listings/${listing.id}/images/presign`,
            presignSchema,
            "POST",
            {
              segment: job.segment,
              contentType: blob.type,
              byteSize: blob.size,
            },
          );
          reservation = { ticket, blob };
        }
        const { ticket } = reservation;
        imageId = ticket.imageId;
        update(job.key, { status: "uploading", imageId, reservation });
        await uploadDirect(ticket, blob, (progress) =>
          update(job.key, { progress }),
        );
        uploaded = true;
        update(job.key, { uploaded: true });
      }
      update(job.key, { status: "validating" });
      await api.request(
        `/api/v1/listings/${listing.id}/images/complete`,
        imageSchema,
        "POST",
        { imageId },
      );
      update(job.key, { status: "complete", progress: 100, error: "" });
      await reload();
    } catch (error) {
      if (
        (error instanceof ApiError && [400, 409, 413].includes(error.status)) ||
        (error instanceof UploadTransportError && error.needsNewReservation)
      ) {
        imageId = undefined;
        uploaded = false;
        reservation = undefined;
      }
      update(job.key, {
        status: "error",
        imageId,
        uploaded,
        reservation,
        error:
          error instanceof ApiError
            ? errorMessage(error)
            : error instanceof Error
              ? error.message
              : "The photo could not be uploaded.",
      });
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel">
      <h2>
        <ImagePlus size={22} className="inline-icon" /> Add photos
      </h2>
      <p>
        Choose JPEG, PNG, or WebP files up to 40 MB each. Your device converts
        each photo to lossless WebP without resizing or a target file size, then
        uploads it directly to storage. Converted files may be larger.
      </p>
      <div className="upload-grid">
        {listing.images.map((image) => (
          <figure key={image.imageId}>
            {image.url && (
              <img
                src={imageUrl(image.url, config.apiOrigin)}
                alt={`Validated ${image.segment.toLowerCase()} photo`}
                referrerPolicy="no-referrer"
              />
            )}
            <figcaption>
              <Check size={14} />
              {image.segment.replaceAll("_", " ")}
            </figcaption>
          </figure>
        ))}
      </div>
      <div className="form-grid">
        <label className="field">
          Photo segment
          <select
            value={segment}
            onChange={(event) =>
              setSegment(segmentSchema.parse(event.target.value))
            }
          >
            {segmentSchema.options.map((value) => (
              <option value={value} key={value}>
                {value.replaceAll("_", " ")}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Choose photos
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            disabled={
              busy ||
              listing.images.length +
                jobs.filter((job) => job.status !== "complete").length >=
                MAX_LISTING_PHOTOS
            }
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              const slots = Math.max(
                0,
                MAX_LISTING_PHOTOS -
                  listing.images.length -
                  jobs.filter((job) => job.status !== "complete").length,
              );
              setJobs((current) => [
                ...current,
                ...files.slice(0, slots).map(
                  (file) =>
                    ({
                      key: crypto.randomUUID(),
                      file,
                      segment,
                      progress: 0,
                      status: "ready",
                      error: "",
                    }) satisfies UploadJob,
                ),
              ]);
              event.target.value = "";
            }}
          />
        </label>
      </div>
      <p className="fine-print">
        Up to {MAX_LISTING_PHOTOS} images per listing, 60 upload reservations
        per rolling 24 hours. Uploaded images cannot be removed or replaced in
        this version.
      </p>
      <ul className="upload-jobs">
        {jobs.map((job) => (
          <li key={job.key}>
            <div>
              <span>
                {job.file.name} · {job.segment.replaceAll("_", " ")}
              </span>
              <p role="status">
                {job.status === "uploading"
                  ? `Uploading ${job.progress}%`
                  : job.status === "validating"
                    ? "Validating photo…"
                    : job.status === "complete"
                      ? "Validated"
                      : job.status === "compressing"
                        ? "Converting to lossless WebP…"
                        : job.status === "error"
                          ? job.error
                          : "Ready to upload"}
              </p>
              {job.status === "uploading" && (
                <progress
                  aria-label={`Upload progress for ${job.file.name}`}
                  max={100}
                  value={job.progress}
                />
              )}
            </div>
            {["ready", "error"].includes(job.status) && (
              <Button
                variant="outline"
                size="small"
                disabled={busy}
                onClick={() => void run(job, Date.now())}
              >
                <Upload size={14} />
                {job.status === "error" ? "Retry photo" : "Upload photo"}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

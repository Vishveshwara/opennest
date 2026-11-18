"use client";

import { useState } from "react";
import Link from "next/link";
import { BedDouble } from "lucide-react";
import { z } from "zod";
import { errorMessage, readApi } from "@/lib/api";
import { imageSchema, type Listing } from "@/lib/contracts";
import { imageUrl, Notice } from "./common";
import { useSession } from "./session";
import { Button } from "./ui/button";

const photoResponse = z.object({ images: z.array(imageSchema) });
type Photos = Listing["images"];

export function ListingPhotos({
  id,
  title,
  images,
  coverHref,
}: {
  id: string;
  title: string;
  images: Photos;
  coverHref?: string;
}) {
  const { config } = useSession();
  const [refreshed, setRefreshed] = useState<{
    base: Photos;
    photos: Photos;
    version: number;
  }>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const current = refreshed?.base === images ? refreshed : undefined;
  const photos = (current?.photos ?? images).flatMap((photo) => {
    const src = photo.url && imageUrl(photo.url, config.apiOrigin);
    return src ? [{ ...photo, src }] : [];
  });

  async function refresh() {
    setPending(true);
    setError("");
    try {
      const { images: photos } = await readApi(
        config.browserApiOrigin,
        `/api/v1/listings/${id}`,
        photoResponse,
        { credentials: "omit" },
      );
      setRefreshed({
        base: images,
        photos,
        version: (current?.version ?? 0) + 1,
      });
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setPending(false);
    }
  }

  const content = (coverHref ? photos.slice(0, 1) : photos).map(
    (photo, index) => (
      <Photo
        key={`${photo.imageId}-${photo.src}-${current?.version ?? 0}`}
        src={photo.src}
        alt={
          coverHref
            ? title
            : `${title}, ${photo.segment.toLowerCase().replaceAll("_", " ")} photo ${index + 1}`
        }
        href={coverHref}
        eager={!coverHref && index === 0}
        pending={pending}
        refresh={refresh}
      />
    ),
  );

  if (coverHref) {
    return (
      <>
        {content.length ? (
          content
        ) : (
          <Link
            href={coverHref}
            className="listing-placeholder"
            aria-label={title}
          >
            <BedDouble size={40} strokeWidth={1} aria-hidden />
          </Link>
        )}
        {error && <Notice error>{error}</Notice>}
      </>
    );
  }

  return (
    <>
      <div className="photo-gallery">
        {content.length ? (
          content
        ) : (
          <p className="listing-placeholder">No photos available</p>
        )}
      </div>
      {error && <Notice error>{error}</Notice>}
    </>
  );
}

function Photo({
  src,
  alt,
  href,
  eager,
  pending,
  refresh,
}: {
  src: string;
  alt: string;
  href?: string;
  eager: boolean;
  pending: boolean;
  refresh: () => Promise<void>;
}) {
  const [failed, setFailed] = useState(false);
  const image = (
    <img
      src={src}
      alt={alt}
      loading={eager ? "eager" : "lazy"}
      fetchPriority={eager ? "high" : undefined}
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      ref={(node) => {
        if (node?.complete && node.naturalWidth === 0) setFailed(true);
      }}
    />
  );
  return (
    <div className="photo-frame">
      {failed ? (
        <div className="photo-fallback">
          <span>Photo unavailable</span>
          <Button
            type="button"
            variant="outline"
            size="small"
            disabled={pending}
            onClick={() => void refresh()}
          >
            {pending ? "Refreshing photos…" : "Refresh photos"}
          </Button>
        </div>
      ) : href ? (
        <Link href={href}>{image}</Link>
      ) : (
        image
      )}
    </div>
  );
}

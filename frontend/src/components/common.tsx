import Link from "next/link";
import { ArrowLeft, ArrowRight, AlertCircle } from "lucide-react";
import type { Badge, Listing } from "@/lib/contracts";
import { Button } from "./ui/button";

export function Notice({
  children,
  error = false,
}: {
  children: React.ReactNode;
  error?: boolean;
}) {
  return (
    <div
      className={error ? "notice notice-error" : "notice"}
      role={error ? "alert" : "status"}
    >
      <AlertCircle size={18} aria-hidden /> <div>{children}</div>
    </div>
  );
}

const badges: Record<Badge, string> = {
  STANDARD: "Community profile",
  TOP_RATED: "Top rated",
  LOW_RATINGS_BEWARE: "Low ratings reported",
  BROKERAGE_WARNED: "Brokerage reported",
  CRITICAL_BROKER_AND_LOW_RATING: "Brokerage reported · Low ratings",
};
export function TrustBadge({ value }: { value: Badge }) {
  return (
    <span
      className={`badge ${value.includes("BROKER") || value === "LOW_RATINGS_BEWARE" ? "badge-warning" : ""}`}
    >
      {badges[value]}
    </span>
  );
}
export function rentLabel(listing: Listing) {
  return `INR ${listing.monthlyRent.toLocaleString("en-IN")} / ${listing.rentBasis === "PER_PERSON" ? "person" : listing.rentBasis === "PER_PROPERTY" ? "property" : "room"} / month`;
}
export function capacityLabel(listing: Listing) {
  return listing.flatRentalMode === "ENTIRE_FLAT"
    ? `Up to ${listing.maxOccupants} people per flat`
    : `Up to ${listing.maxSharingPerRoom} people per room`;
}
export function modeLabel(listing: Listing) {
  return listing.category === "PG_HOSTEL"
    ? "PG / Hostel"
    : listing.flatRentalMode === "ENTIRE_FLAT"
      ? "Entire flat"
      : "Room in a flat";
}
export function imageUrl(value: string, apiOrigin: string) {
  try {
    const url = new URL(value, apiOrigin || undefined);
    return ["https:", "http:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : "";
  } catch {
    return "";
  }
}

export function Pagination({
  page,
  size,
  totalPages,
  totalElements,
  href,
}: {
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
  href: (page: number) => string;
}) {
  const last = Math.min(totalPages - 1, Math.floor(10000 / size));
  const start = Math.max(0, Math.min(page - 2, last - 4));
  const pages = Array.from(
    { length: Math.max(0, Math.min(5, last - start + 1)) },
    (_, i) => start + i,
  );
  return (
    <div className="pagination-wrap">
      <p>
        {totalElements.toLocaleString("en-IN")} matching{" "}
        {totalElements === 1 ? "place" : "places"}
      </p>
      <nav className="pagination" aria-label="Pagination">
        {page > 0 && totalPages > 0 ? (
          <Button asChild variant="outline" size="small">
            <Link
              href={href(Math.min(page - 1, last))}
              aria-label="Previous page"
            >
              <ArrowLeft size={15} /> Previous
            </Link>
          </Button>
        ) : (
          <Button variant="outline" size="small" disabled>
            Previous
          </Button>
        )}
        {pages.map((value) => (
          <Button
            key={value}
            asChild
            variant={value === page ? "default" : "ghost"}
            size="small"
          >
            <Link
              href={href(value)}
              aria-current={value === page ? "page" : undefined}
              aria-label={`Page ${value + 1}`}
            >
              {value + 1}
            </Link>
          </Button>
        ))}
        {page < last ? (
          <Button asChild variant="outline" size="small">
            <Link href={href(page + 1)} aria-label="Next page">
              Next <ArrowRight size={15} />
            </Link>
          </Button>
        ) : (
          <Button variant="outline" size="small" disabled>
            Next
          </Button>
        )}
      </nav>
      {page >= last && totalPages - 1 > last && (
        <p>
          Narrow your filters to explore more results within the browsing limit.
        </p>
      )}
    </div>
  );
}

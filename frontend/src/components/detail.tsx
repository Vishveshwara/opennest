import Link from "next/link";
import { ArrowLeft, Dumbbell, MapPin, Star, Waves, Wifi } from "lucide-react";
import type { Listing } from "@/lib/contracts";
import { TrustBadge, capacityLabel, modeLabel, rentLabel } from "./common";
import { ListingContact, ListingReviews } from "./listing-interactions";
import { ListingMap } from "./listing-map";
import { ListingPhotos } from "./listing-photos";

export function Detail({
  listing,
  basePath,
}: {
  listing: Listing;
  basePath: string;
}) {
  const listingIdentity = { id: listing.id, ownerId: listing.ownerId };
  return (
    <div className="container section">
      <Link href={`${basePath}/`} className="back-link">
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
      <ListingPhotos
        id={listing.id}
        title={listing.title}
        images={listing.images}
      />
      <div className="detail-columns">
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
            <ListingContact listing={listingIdentity} />
          </div>
        </aside>
        <div className="detail-content">
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
            <ListingMap
              listing={{
                id: listing.id,
                publicLocation: listing.publicLocation,
              }}
            />
          </section>
          <ListingReviews listing={listingIdentity} />
        </div>
      </div>
    </div>
  );
}

import Link from "next/link";
import { BedDouble, Dumbbell, MapPin, Users, Waves, Wifi } from "lucide-react";
import type { Listing } from "@/lib/contracts";
import { TrustBadge, capacityLabel, modeLabel, rentLabel } from "./common";
import { ListingPhotos } from "./listing-photos";

export function ListingCard({
  listing,
  basePath,
}: {
  listing: Listing;
  basePath: string;
}) {
  const href = `${basePath}/listings/${listing.id}`;
  return (
    <>
      <div className="listing-cover">
        <ListingPhotos
          id={listing.id}
          title={listing.title}
          images={listing.images}
          coverHref={href}
        />
        <span className="photo-badge">{modeLabel(listing)}</span>
        {listing.status === "FULL_OCCUPIED" && (
          <span className="occupied-badge">Fully occupied</span>
        )}
      </div>
      <div className="listing-body">
        <div className="card-location">
          <MapPin size={13} aria-hidden />
          {listing.locality}, {listing.city}
        </div>
        <h3>
          <Link href={href}>{listing.title}</Link>
        </h3>
        <div className="listing-specs">
          {listing.bedrooms !== null && (
            <span>
              <BedDouble size={15} aria-hidden />
              {listing.bedrooms === 0 ? "Studio" : `${listing.bedrooms} BHK`}
            </span>
          )}
          <span>
            <Users size={15} aria-hidden />
            {capacityLabel(listing)}
          </span>
        </div>
        <p className="vacancy">
          {listing.flatRentalMode === "ENTIRE_FLAT"
            ? listing.wholeFlatAvailable
              ? "Whole flat available"
              : "Whole flat occupied"
            : `${listing.totalRoomsAvailable} rooms available`}
        </p>
        <div className="card-price">{rentLabel(listing)}</div>
        <div className="card-bottom">
          <TrustBadge value={listing.owner.trustBadge} />
          <span className="amenity-icons">
            {listing.hasWifi && <Wifi aria-label="Wi-Fi" size={16} />}
            {listing.hasGym && <Dumbbell aria-label="Gym" size={16} />}
            {listing.hasSwimmingPool && (
              <Waves aria-label="Swimming pool" size={16} />
            )}
          </span>
        </div>
      </div>
    </>
  );
}

package in.opennest.listing;

import java.util.UUID;

public interface ListingInteractions {
    ListingSubject find(UUID id, boolean lock);
    record ListingSubject(UUID ownerId, boolean published) {}
}

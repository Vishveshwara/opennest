package in.opennest.listing.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.identity.CurrentAccount;
import in.opennest.identity.IdentityAccounts;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Service
class ListingService {
    private final ListingStore store;
    private final CurrentAccount current;
    private final IdentityAccounts accounts;
    private final ListingMedia media;
    ListingService(ListingStore store, CurrentAccount current, IdentityAccounts accounts, ListingMedia media) {
        this.store = store; this.current = current; this.accounts = accounts; this.media = media;
    }
    UUID caller() { return current.id().orElseThrow(() -> new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED)); }
    Map<String, Object> create(JsonNode body) {
        var owner = caller(); var draft = ListingInput.draft(body);
        return store.write(() -> {
            accounts.lockForListingMutation(owner);
            if (store.count(owner, false) >= 20) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
            var id = store.create(owner, draft);
            return render(store.property(id, false), true);
        });
    }
    Map<String, Object> detail(UUID id) {
        return store.read(() -> {
            var row = store.property(id, false);
            boolean owner = current.id().filter(row.get("owner_id")::equals).isPresent();
            if ("PAUSED".equals(row.get("status")) && !owner) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
            return render(row, owner);
        });
    }
    Map<String, Object> search(MultiValueMap<String, String> query, boolean mine) {
        var owner = mine ? caller() : null;
        var search = ListingSearch.parse(query, mine);
        return store.read(() -> store.page(search, owner, media));
    }
    Map<String, Object> change(UUID id, JsonNode body, String action) {
        var owner = caller();
        ListingInput.fields(body, action.equals("inventory") ? Set.of("version", "wholeFlatAvailable", "maxOccupants", "maleRoomsAvailable",
                "femaleRoomsAvailable", "sharedRoomsAvailable", "maxSharingPerRoom") : Set.of("version"));
        long version = ListingInput.version(body);
        return store.write(() -> {
            accounts.lockForListingMutation(owner);
            var row = store.owned(id, owner, true);
            store.version(row, version);
            boolean paused = "PAUSED".equals(row.get("status"));
            switch (action) {
                case "inventory" -> store.updateInventory(id, version, ListingInput.inventory(body, "ENTIRE_FLAT".equals(row.get("flat_rental_mode"))), paused);
                case "pause" -> { if (!paused) store.status(id, version, "PAUSED"); }
                case "publish" -> {
                    if (paused) {
                        if (!accounts.hasVerifiedContact(owner)) throw new RequestFailure(RequestFailure.Kind.CONTACT_VERIFICATION_REQUIRED);
                        var required = "FLAT".equals(row.get("category")) ? Set.of("ROOM", "HALL", "BATHROOM", "KITCHEN") : Set.of("ROOM", "BATHROOM");
                        var segments = store.jdbc().queryForList("select distinct segment from property_images where property_id = ? and validation_state = 'VALIDATED'", String.class, id);
                        if (!segments.containsAll(required) || store.count(owner, true) >= 5) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
                        boolean available = "ENTIRE_FLAT".equals(row.get("flat_rental_mode")) ? Boolean.TRUE.equals(row.get("whole_flat_available"))
                                : ((Number) row.get("total_rooms_available")).longValue() > 0;
                        store.status(id, version, available ? "ACTIVE" : "FULL_OCCUPIED");
                    }
                }
                default -> throw new IllegalArgumentException();
            }
            return render(store.property(id, false), true);
        });
    }
    private Map<String, Object> render(Map<String, Object> row, boolean owner) {
        var id = (UUID) row.get("id");
        return store.response(row, owner, store.images(List.of(id)).getOrDefault(id, List.of()), media);
    }
}

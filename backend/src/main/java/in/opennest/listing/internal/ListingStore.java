package in.opennest.listing.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import in.opennest.identity.TrustBadge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class ListingStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final SandboxBoundary boundary;
    private static final String JOIN = " from properties p join listing_owner_profiles o on o.id = p.owner_id ";
    private static final String SELECT = "select p.*, o.full_name, o.average_rating, o.review_count, o.broker_report_count";

    ListingStore(JdbcTemplate jdbc, PlatformTransactionManager manager, SandboxBoundary boundary) {
        this.jdbc = jdbc; named = new NamedParameterJdbcTemplate(jdbc);
        this.boundary = boundary;
        jdbc.setQueryTimeout(10);
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(!boundary.sandbox());
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    <T> T write(Supplier<T> operation) { return execute(writes, operation); }
    JdbcTemplate jdbc() { return jdbc; }
    boolean sandbox() { return boundary.sandbox(); }
    <T> T read(Supplier<T> operation) { return execute(reads, operation); }
    /**
     * Runs listing work under the sandbox reset guard and preserves the initial failure if rollback fails.
     */
    private <T> T execute(TransactionTemplate template, Supplier<T> operation) {
        var original = new RuntimeException[1];
        try {
            return template.execute(status -> {
                try { boundary.lock(); return operation.get(); }
                catch (RuntimeException failure) { original[0] = failure; throw failure; }
            });
        } catch (RuntimeException failure) {
            if (original[0] != null && original[0] != failure) { original[0].addSuppressed(failure); throw original[0]; }
            throw failure;
        }
    }

    Map<String, Object> property(UUID id, boolean lock) {
        var rows = jdbc.queryForList(SELECT + JOIN + "where p.id = ?" + (lock ? " for update of p" : ""), id);
        if (rows.isEmpty()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return rows.getFirst();
    }
    Map<String, Object> owned(UUID id, UUID owner, boolean lock) {
        var row = property(id, lock);
        if (!owner.equals(row.get("owner_id"))) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
        return row;
    }
    void version(Map<String, Object> row, long expected) {
        if (((Number) row.get("version")).longValue() != expected) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
    }
    long count(UUID owner, boolean published) {
        return jdbc.queryForObject("select count(*) from properties where owner_id = ?" + (published ? " and status <> 'PAUSED'" : ""), Long.class, owner);
    }
    UUID create(UUID owner, ListingInput.Draft draft) {
        var id = UUID.randomUUID();
        var values = new LinkedHashMap<String, Object>();
        values.put("id", id); values.put("owner_id", owner); values.put("title", draft.title()); values.put("description", draft.description());
        values.put("category", draft.category().name()); values.put("flat_rental_mode", draft.mode() == null ? null : draft.mode().name());
        values.put("city", draft.city()); values.put("locality", draft.locality()); values.put("private_address", draft.address());
        values.put("latitude", draft.latitude()); values.put("longitude", draft.longitude()); values.put("monthly_rent", draft.rent());
        values.put("security_deposit", draft.deposit()); values.put("bedrooms", draft.bedrooms());
        values.put("has_wifi", draft.wifi()); values.put("has_gym", draft.gym()); values.put("has_swimming_pool", draft.pool());
        inventoryValues(values, draft.inventory());
        named.update("insert into properties (" + String.join(",", values.keySet()) + ") values (:" + String.join(",:", values.keySet()) + ")", values);
        return id;
    }
    void updateInventory(UUID id, long version, ListingInput.Inventory inventory, boolean paused) {
        var values = new LinkedHashMap<String, Object>();
        inventoryValues(values, inventory);
        var updates = new ArrayList<String>();
        values.keySet().forEach(key -> updates.add(key + " = :" + key));
        updates.add("status = :status"); updates.add("version = version + 1"); updates.add("updated_at = greatest(created_at, current_timestamp)");
        values.put("status", paused ? "PAUSED" : inventory.publishedStatus().name()); values.put("id", id); values.put("version", version);
        int changed = named.update("update properties set " + String.join(",", updates) + " where id = :id and version = :version", values);
        if (changed != 1) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
    }
    void status(UUID id, long version, String status) {
        if (jdbc.update("update properties set status = ?, version = version + 1, updated_at = greatest(created_at, current_timestamp) where id = ? and version = ?",
                status, id, version) != 1) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
    }
    private void inventoryValues(Map<String, Object> values, ListingInput.Inventory inventory) {
        switch (inventory) {
            case ListingInput.EntireFlat flat -> { values.put("whole_flat_available", flat.available()); values.put("max_occupants", flat.capacity()); }
            case ListingInput.Rooms rooms -> {
                values.put("male_rooms_available", rooms.male()); values.put("female_rooms_available", rooms.female());
                values.put("shared_rooms_available", rooms.shared()); values.put("max_sharing_per_room", rooms.sharing());
            }
        }
    }

    Map<String, Object> page(ListingSearch search, UUID owner, ListingMedia media) {
        var parameters = new LinkedHashMap<>(search.parameters());
        String where = search.where();
        if (owner != null) { where = (where.isEmpty() ? "" : where + " and ") + "p.owner_id = :owner"; parameters.put("owner", owner); }
        long count = named.queryForObject("select count(*)" + JOIN + "where " + where, parameters, Long.class);
        parameters.put("limit", search.size()); parameters.put("offset", (long) search.page() * search.size());
        var rows = named.queryForList(SELECT + JOIN + "where " + where + " order by " + search.order() + " limit :limit offset :offset", parameters);
        var ids = rows.stream().map(row -> (UUID) row.get("id")).toList();
        var images = images(ids);
        var items = rows.stream().map(row -> response(row, owner != null, images.getOrDefault((UUID) row.get("id"), List.of()), media)).toList();
        long pages = (count + search.size() - 1) / search.size();
        return Map.of("items", items, "page", search.page(), "size", search.size(), "totalElements", count, "totalPages", pages,
                "hasNext", search.page() + 1L < pages, "hasPrevious", search.page() > 0 && pages > 0, "sort", search.sort());
    }
    Map<UUID, List<Map<String, Object>>> images(List<UUID> ids) {
        var result = new LinkedHashMap<UUID, List<Map<String, Object>>>();
        if (ids.isEmpty()) return result;
        named.queryForList("select * from property_images where property_id in (:ids) and validation_state = 'VALIDATED' order by display_order, id", Map.of("ids", ids))
                .forEach(row -> result.computeIfAbsent((UUID) row.get("property_id"), ignored -> new ArrayList<>()).add(row));
        return result;
    }
    Map<String, Object> response(Map<String, Object> row, boolean owner, List<Map<String, Object>> images, ListingMedia media) {
        var response = new LinkedHashMap<String, Object>();
        var fields = Map.ofEntries(Map.entry("id", "id"), Map.entry("ownerId", "owner_id"), Map.entry("title", "title"),
                Map.entry("description", "description"), Map.entry("category", "category"), Map.entry("flatRentalMode", "flat_rental_mode"),
                Map.entry("status", "status"), Map.entry("city", "city"), Map.entry("locality", "locality"), Map.entry("monthlyRent", "monthly_rent"),
                Map.entry("securityDeposit", "security_deposit"), Map.entry("bedrooms", "bedrooms"), Map.entry("hasWifi", "has_wifi"),
                Map.entry("hasGym", "has_gym"), Map.entry("hasSwimmingPool", "has_swimming_pool"), Map.entry("wholeFlatAvailable", "whole_flat_available"),
                Map.entry("maxOccupants", "max_occupants"), Map.entry("maleRoomsAvailable", "male_rooms_available"), Map.entry("femaleRoomsAvailable", "female_rooms_available"),
                Map.entry("sharedRoomsAvailable", "shared_rooms_available"), Map.entry("totalRoomsAvailable", "total_rooms_available"), Map.entry("maxSharingPerRoom", "max_sharing_per_room"));
        fields.forEach((key, column) -> response.put(key, row.get(column)));
        response.put("createdAt", instant(row.get("created_at"))); response.put("updatedAt", instant(row.get("updated_at")));
        response.put("currency", "INR"); response.put("rentPeriod", "MONTH");
        response.put("rentBasis", "PG_HOSTEL".equals(row.get("category")) ? "PER_PERSON" : "ENTIRE_FLAT".equals(row.get("flat_rental_mode")) ? "PER_PROPERTY" : "PER_ROOM");
        long reviews = ((Number) row.get("review_count")).longValue(), reports = ((Number) row.get("broker_report_count")).longValue();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("fullName", row.get("full_name")); summary.put("averageRating", row.get("average_rating"));
        summary.put("reviewCount", reviews); summary.put("brokerReportCount", reports);
        summary.put("trustBadge", TrustBadge.calculate((BigDecimal) row.get("average_rating"), reviews, reports));
        response.put("owner", summary); response.put("nearbyLandmarks", List.of());
        response.put("images", images.stream().map(media::imageResponse).toList());
        if (owner) {
            response.put("version", row.get("version")); response.put("privateAddress", row.get("private_address"));
            response.put("latitude", row.get("latitude")); response.put("longitude", row.get("longitude"));
        }
        return response;
    }
    static Instant instant(Object value) { return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.OffsetDateTime) value).toInstant(); }
}

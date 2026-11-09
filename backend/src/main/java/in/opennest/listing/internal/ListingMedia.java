package in.opennest.listing.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import in.opennest.common.RequestFailure;
import in.opennest.identity.IdentityAccounts;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
class ListingMedia implements AutoCloseable {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private final ListingStore store;
    private final StorageObjects storage;
    private final IdentityAccounts accounts;
    private final Semaphore processors = new Semaphore(2);
    private final java.util.concurrent.ExecutorService decoders = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), Thread.ofPlatform().daemon().name("opennest-image-", 0).factory());

    ListingMedia(ListingStore store, StorageObjects storage, IdentityAccounts accounts) {
        this.store = store; this.storage = storage; this.accounts = accounts;
    }
    Map<String, Object> presign(UUID property, UUID owner, JsonNode body) {
        requireStorage();
        ListingInput.fields(body, Set.of("segment", "contentType", "byteSize"));
        String segment = ListingInput.enumeration(body, "segment", ListingInput.Segment.class).name();
        String type = ListingInput.text(body, "contentType", 32);
        ListingInput.require(Set.of("image/jpeg", "image/png", "image/webp").contains(type));
        int size = ListingInput.integer(body, "byteSize", 1, MAX_BYTES);
        return store.write(() -> {
            accounts.lockForListingMutation(owner); store.owned(property, owner, true);
            long count = store.jdbc().queryForObject("select count(*) from property_images where property_id = ? and (validation_state = 'VALIDATED' or (validation_state = 'PENDING' and upload_expires_at > current_timestamp))", Long.class, property);
            if (count >= 12) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
            long recent = store.jdbc().queryForObject("select count(*) from property_images i join properties p on p.id = i.property_id where p.owner_id = ? and i.created_at > current_timestamp - interval '24 hours'", Long.class, owner);
            if (recent >= 60) throw new RequestFailure(RequestFailure.Kind.RATE_LIMITED);
            var id = UUID.randomUUID(); String key = "listings/" + property + "/" + id;
            var upload = storage.upload(key, type, size);
            Instant expires = Instant.now().plus(Duration.ofMinutes(10));
            store.jdbc().update("insert into property_images (id, property_id, upload_object_key, segment, display_order, upload_expires_at, declared_content_type, declared_byte_size) values (?,?,?,?,?,?,?,?)",
                    id, property, key, segment, (int) store.jdbc().queryForObject("select coalesce(max(display_order),-1)+1 from property_images where property_id = ?", Integer.class, property), Timestamp.from(expires), type, size);
            return Map.of("imageId", id, "objectKey", key, "uploadUrl", upload.url(), "expiresAt", expires, "requiredHeaders", upload.headers());
        });
    }
    Map<String, Object> complete(UUID property, UUID owner, JsonNode body) {
        requireStorage(); ListingInput.fields(body, Set.of("imageId"));
        UUID id;
        try { id = UUID.fromString(ListingInput.text(body, "imageId", 36)); } catch (IllegalArgumentException failure) { throw ListingInput.invalid(); }
        var row = store.read(() -> { store.owned(property, owner, false); return image(property, id, false); });
        if ("VALIDATED".equals(row.get("validation_state"))) return imageResponse(row);
        checkPending(row);
        if (!processors.tryAcquire()) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        long started = System.nanoTime();
        String key = "listings/" + property + "/" + id + "/" + UUID.randomUUID();
        try {
            var bytes = storage.read((String) row.get("upload_object_key"), ((Number) row.get("declared_byte_size")).longValue());
            var validated = decode(bytes, (String) row.get("declared_content_type"));
            storage.writeFinal(key, validated.bytes(), validated.type());
            return store.write(() -> {
                accounts.lockForListingMutation(owner); store.owned(property, owner, true);
                var locked = image(property, id, true);
                if ("VALIDATED".equals(locked.get("validation_state"))) return imageResponse(locked);
                if (System.nanoTime() - started > Duration.ofMinutes(2).toNanos()) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
                checkPending(locked);
                store.jdbc().update("update property_images set object_key = ?, validation_state = 'VALIDATED', content_type = ?, byte_size = ?, width = ?, height = ? where id = ?",
                        key, validated.type(), validated.bytes().length, validated.width(), validated.height(), id);
                return imageResponse(image(property, id, false));
            });
        } catch (RequestFailure failure) {
            if (failure.kind() == RequestFailure.Kind.INVALID_INPUT) store.write(() -> {
                accounts.lockForListingMutation(owner); store.owned(property, owner, true);
                store.jdbc().update("update property_images set validation_state = 'REJECTED' where id = ? and validation_state = 'PENDING'", id); return null;
            });
            throw failure;
        } finally { processors.release(); }
    }
    private Map<String, Object> image(UUID property, UUID id, boolean lock) {
        var rows = store.jdbc().queryForList("select * from property_images where id = ? and property_id = ?" + (lock ? " for update" : ""), id, property);
        if (rows.isEmpty()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return rows.getFirst();
    }
    private void checkPending(Map<String, Object> row) {
        if (!"PENDING".equals(row.get("validation_state")) || row.get("declared_byte_size") == null || row.get("declared_content_type") == null
                || !ListingStore.instant(row.get("upload_expires_at")).isAfter(Instant.now()))
            throw new RequestFailure(RequestFailure.Kind.CONFLICT);
    }
    Map<String, Object> imageResponse(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("imageId", row.get("id")); result.put("segment", row.get("segment")); result.put("displayOrder", row.get("display_order"));
        result.put("contentType", row.get("content_type")); result.put("byteSize", row.get("byte_size")); result.put("width", row.get("width")); result.put("height", row.get("height"));
        result.put("url", storage.enabled() ? storage.download((String) row.get("object_key")) : null);
        result.put("expiresAt", storage.enabled() ? Instant.now().plus(Duration.ofMinutes(5)) : null);
        return result;
    }
    private void requireStorage() { if (!storage.enabled()) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE); }
    private ImageValidator.Validated decode(byte[] bytes, String type) {
        java.util.concurrent.Future<ImageValidator.Validated> task;
        try { task = decoders.submit(() -> ImageValidator.validate(bytes, type)); }
        catch (java.util.concurrent.RejectedExecutionException failure) { throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE); }
        try { return task.get(20, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("OpenNest image processing failed");
        } catch (java.util.concurrent.TimeoutException failure) {
            task.cancel(true); throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        } catch (InterruptedException failure) {
            task.cancel(true); Thread.currentThread().interrupt(); throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        }
    }
    @Override public void close() { decoders.shutdownNow(); }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void cleanup() {
        if (!storage.enabled()) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        storage.visit(StorageObjects.Bucket.STAGING, object -> { if (object.modified().isBefore(cutoff)) storage.delete(StorageObjects.Bucket.STAGING, object.key()); });
        storage.visit(StorageObjects.Bucket.FINAL, object -> {
            if (object.modified().isBefore(cutoff) && store.jdbc().queryForObject("select count(*) from property_images where object_key = ?", Long.class, object.key()) == 0)
                storage.delete(StorageObjects.Bucket.FINAL, object.key());
        });
        store.write(() -> {
            store.jdbc().update("update property_images set validation_state = 'REJECTED' where validation_state = 'PENDING' and upload_expires_at < current_timestamp - interval '24 hours'");
            store.jdbc().update("delete from property_images where validation_state = 'REJECTED' and created_at < current_timestamp - interval '7 days'");
            return null;
        });
    }
}

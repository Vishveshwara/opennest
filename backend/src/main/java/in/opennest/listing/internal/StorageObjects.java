package in.opennest.listing.internal;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

interface StorageObjects {
    enum Bucket { STAGING, FINAL }
    record Upload(String url, Map<String, String> headers) {
        @Override public String toString() { return "Upload[private]"; }
    }
    record Stored(String key, Instant modified) {}
    boolean enabled();
    Upload upload(String key, String type, long bytes);
    byte[] read(String key, long expectedSize);
    void writeFinal(String key, byte[] bytes, String type);
    String download(String key);
    void delete(Bucket bucket, String key);
    void visit(Bucket bucket, Consumer<Stored> visitor);
}

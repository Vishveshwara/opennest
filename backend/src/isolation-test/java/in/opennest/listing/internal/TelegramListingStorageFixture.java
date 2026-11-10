package in.opennest.listing.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TelegramListingStorageFixture {
    @Bean @Primary public ControlledListingStorage telegramListingStorage() { return new ControlledListingStorage(); }

    public static final class ControlledListingStorage implements StorageObjects {
        private final Map<String, byte[]> staging = new ConcurrentHashMap<>();
        private final Map<String, byte[]> finals = new ConcurrentHashMap<>();
        public void stage(String key, byte[] bytes) { staging.put(key, bytes.clone()); }
        @Override public boolean enabled() { return true; }
        @Override public Upload upload(String key, String type, long bytes) {
            return new Upload("https://storage.example.invalid/" + key, Map.of("Content-Type", type));
        }
        @Override public byte[] read(String key, long expectedSize) {
            var bytes = staging.get(key);
            ListingInput.require(bytes != null && bytes.length == expectedSize);
            return bytes.clone();
        }
        @Override public void writeFinal(String key, byte[] bytes, String type) {
            if (finals.putIfAbsent(key, bytes.clone()) != null) throw new IllegalStateException("Duplicate fixture object");
        }
        @Override public String download(String key) { return "https://media.example.invalid/" + key; }
        @Override public void delete(Bucket bucket, String key) { objects(bucket).remove(key); }
        @Override public void visit(Bucket bucket, Consumer<Stored> visitor) {
            List.copyOf(objects(bucket).keySet()).forEach(key -> visitor.accept(new Stored(key, Instant.now())));
        }
        private Map<String, byte[]> objects(Bucket bucket) { return bucket == Bucket.STAGING ? staging : finals; }
    }
}

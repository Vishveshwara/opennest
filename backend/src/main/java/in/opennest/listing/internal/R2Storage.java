package in.opennest.listing.internal;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import in.opennest.common.RequestFailure;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
class R2Storage implements StorageObjects, AutoCloseable {
    private static final Duration STORAGE_DEADLINE = Duration.ofSeconds(120);
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final S3Client client;
    private final S3Presigner signer;
    private final String staging;
    private final String finals;
    private final Duration readDeadline;

    R2Storage(S3Client client, S3Presigner signer, String staging, String finals) {
        this(client, signer, staging, finals, STORAGE_DEADLINE);
    }

    R2Storage(S3Client client, S3Presigner signer, String staging, String finals, Duration readDeadline) {
        if (readDeadline.isNegative() || readDeadline.isZero()) throw new IllegalArgumentException();
        this.client = client; this.signer = signer; this.staging = staging; this.finals = finals;
        this.readDeadline = readDeadline;
    }

    @org.springframework.beans.factory.annotation.Autowired
    R2Storage(Environment environment) {
        readDeadline = STORAGE_DEADLINE;
        boolean production = "PRODUCTION".equals(environment.getProperty("opennest.deployment.environment", "DEVELOPMENT"));
        boolean sandbox = "SANDBOX".equals(environment.getProperty("opennest.deployment.data-mode", "PRIMARY"));
        var primary = settings(environment, "");
        var demo = settings(environment, "SANDBOX_");
        if (production && (!demo.get("access").isBlank() || !demo.get("secret").isBlank())) throw invalidSettings();
        for (String key : java.util.List.of("access", "secret", "staging", "final")) {
            if (!primary.get(key).isBlank() && primary.get(key).equals(demo.get(key))) throw invalidSettings();
        }
        for (String primaryBucket : java.util.List.of(primary.get("staging"), primary.get("final")))
            for (String sandboxBucket : java.util.List.of(demo.get("staging"), demo.get("final")))
                if (!primaryBucket.isBlank() && primaryBucket.equals(sandboxBucket)) throw invalidSettings();
        String flag = environment.getProperty("OPENNEST_STORAGE_ENABLED", "false");
        if (!flag.equals("true") && !flag.equals("false")) throw invalidSettings();
        if (flag.equals("false")) { client = null; signer = null; staging = ""; finals = ""; return; }
        var selected = sandbox ? demo : primary;
        if (selected.values().stream().anyMatch(String::isBlank) || !selected.get("account").matches("[a-fA-F0-9]{32}")) throw invalidSettings();
        staging = selected.get("staging"); finals = selected.get("final");
        if (staging.equals(finals) || !staging.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                || !finals.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw invalidSettings();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(selected.get("access"), selected.get("secret")));
        var endpoint = URI.create("https://" + selected.get("account") + ".r2.cloudflarestorage.com");
        var configuration = S3Configuration.builder().chunkedEncodingEnabled(false).pathStyleAccessEnabled(true).build();
        client = S3Client.builder().credentialsProvider(credentials).endpointOverride(endpoint).region(Region.of("auto"))
                .serviceConfiguration(configuration).overrideConfiguration(builder -> builder.apiCallTimeout(STORAGE_DEADLINE)
                        .apiCallAttemptTimeout(Duration.ofSeconds(60))).build();
        signer = S3Presigner.builder().credentialsProvider(credentials).endpointOverride(endpoint).region(Region.of("auto"))
                .serviceConfiguration(configuration).build();
    }
    private static Map<String, String> settings(Environment environment, String prefix) {
        return Map.of("account", environment.getProperty(prefix + "R2_ACCOUNT_ID", ""), "access", environment.getProperty(prefix + "R2_ACCESS_KEY_ID", ""),
                "secret", environment.getProperty(prefix + "R2_SECRET_KEY", ""), "staging", environment.getProperty(prefix + "R2_STAGING_BUCKET", ""),
                "final", environment.getProperty(prefix + "R2_FINAL_BUCKET", ""));
    }
    private static IllegalStateException invalidSettings() { return new IllegalStateException("Invalid OpenNest storage settings or isolation"); }
    @Override public boolean enabled() { return client != null; }
    private void requireEnabled() { if (!enabled()) throw unavailable(); }
    private static RequestFailure unavailable() { return new RequestFailure(RequestFailure.Kind.UNAVAILABLE); }
    @Override public Upload upload(String key, String type, long bytes) {
        requireEnabled();
        var request = PutObjectRequest.builder().bucket(staging).key(key).contentType(type).contentLength(bytes).ifNoneMatch("*").build();
        var signed = signer.presignPutObject(builder -> builder.signatureDuration(Duration.ofMinutes(10)).putObjectRequest(request));
        var headers = new LinkedHashMap<String, String>();
        signed.signedHeaders().forEach((name, values) -> { if (!name.equalsIgnoreCase("host")) headers.put(name, String.join(",", values)); });
        return new Upload(signed.url().toString(), headers);
    }
    @Override public byte[] read(String key, long expectedSize) {
        requireEnabled();
        try (var deadline = new ReadDeadline(readDeadline)) {
            var remaining = deadline.remaining();
            var head = client.headObject(builder -> builder.bucket(staging).key(key).overrideConfiguration(configuration -> configuration
                    .apiCallTimeout(remaining).apiCallAttemptTimeout(remaining.compareTo(Duration.ofSeconds(60)) < 0 ? remaining : Duration.ofSeconds(60))));
            if (head.contentLength() != expectedSize || head.contentLength() > ListingMedia.MAX_BYTES) throw ListingInput.invalid();
            var bodyRemaining = deadline.remaining();
            try (var stream = client.getObject(builder -> builder.bucket(staging).key(key).ifMatch(head.eTag())
                    .overrideConfiguration(configuration -> configuration.apiCallTimeout(bodyRemaining)
                            .apiCallAttemptTimeout(bodyRemaining.compareTo(Duration.ofSeconds(60)) < 0 ? bodyRemaining : Duration.ofSeconds(60))))) {
                deadline.attach(stream);
                var bytes = stream.readNBytes(ListingMedia.MAX_BYTES + 1);
                deadline.remaining();
                ListingInput.require(bytes.length == expectedSize);
                return bytes;
            }
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 || failure.statusCode() == 412) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
            throw unavailable();
        } catch (java.io.IOException | software.amazon.awssdk.core.exception.SdkClientException failure) { throw unavailable(); }
    }
    @Override public void writeFinal(String key, byte[] bytes, String type) {
        requireEnabled();
        try {
            client.putObject(PutObjectRequest.builder().bucket(finals).key(key).contentType(type).cacheControl("private, no-store")
                    .ifNoneMatch("*").build(), RequestBody.fromBytes(bytes));
        } catch (software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public String download(String key) {
        requireEnabled();
        return signer.presignGetObject(builder -> builder.signatureDuration(Duration.ofMinutes(5)).getObjectRequest(request -> request
                .bucket(finals).key(key).responseCacheControl("private, no-store"))).url().toString();
    }
    @Override public void delete(Bucket bucket, String key) {
        requireEnabled();
        try { client.deleteObject(builder -> builder.bucket(bucket == Bucket.STAGING ? staging : finals).key(key)); }
        catch (software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public void visit(Bucket bucket, Consumer<Stored> visitor) {
        requireEnabled();
        try {
            var request = ListObjectsV2Request.builder().bucket(bucket == Bucket.STAGING ? staging : finals).prefix("listings/").maxKeys(1000).build();
            for (var page : client.listObjectsV2Paginator(request)) for (var object : page.contents()) visitor.accept(new Stored(object.key(), object.lastModified()));
        } catch (software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public void close() { if (client != null) client.close(); if (signer != null) signer.close(); }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("opennest-storage-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class ReadDeadline implements AutoCloseable {
        private final long started = System.nanoTime();
        private final long duration;
        private final AtomicBoolean expired = new AtomicBoolean();
        private final AtomicReference<ResponseInputStream<GetObjectResponse>> response = new AtomicReference<>();
        private final ScheduledFuture<?> alarm;

        ReadDeadline(Duration timeout) {
            duration = timeout.toNanos();
            alarm = DEADLINES.schedule(() -> {
                expired.set(true);
                var stream = response.get();
                if (stream != null) stream.abort();
            }, duration, TimeUnit.NANOSECONDS);
        }
        Duration remaining() {
            long nanos = duration - (System.nanoTime() - started);
            if (expired.get() || nanos <= 0) throw unavailable();
            return Duration.ofNanos(nanos);
        }
        void attach(ResponseInputStream<GetObjectResponse> stream) {
            response.set(stream);
            if (expired.get() || System.nanoTime() - started >= duration) {
                stream.abort();
                throw unavailable();
            }
        }
        @Override public void close() { alarm.cancel(false); }
    }
}

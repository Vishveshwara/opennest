package in.opennest.listing.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R2StorageTests {
    @Test void abortsTricklingStagingBodyAtTheTotalDeadline() throws Exception {
        try (var fixture = new StorageFixture(Duration.ofSeconds(1))) {
            fixture.chunkDelay = Duration.ofMillis(150);
            long started = System.nanoTime();
            failure(RequestFailure.Kind.UNAVAILABLE, () -> fixture.storage.read("listings/draft/image", fixture.bytes.length));
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(elapsed).isBetween(Duration.ofMillis(600), Duration.ofSeconds(3));
            assertThat(fixture.getCalls.get()).isEqualTo(1);
            assertThat(fixture.getIfMatch).isEqualTo("\"staging-etag\"");
            assertThat(fixture.bodyFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.sentBytes.get()).isLessThan(fixture.bytes.length);
        }
    }

    @Test void headAndBodyShareOneTotalDeadline() throws Exception {
        try (var fixture = new StorageFixture(Duration.ofSeconds(1))) {
            fixture.headDelay = Duration.ofMillis(600);
            fixture.chunkDelay = Duration.ofMillis(150);
            long started = System.nanoTime();
            failure(RequestFailure.Kind.UNAVAILABLE, () -> fixture.storage.read("listings/draft/image", fixture.bytes.length));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(600), Duration.ofMillis(1600));
            assertThat(fixture.getCalls.get()).isEqualTo(1);
            assertThat(fixture.bodyFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.sentBytes.get()).isLessThan(5);
        }
    }

    @Test void readsTheHeadObjectThroughAConditionalGet() throws Exception {
        try (var fixture = new StorageFixture()) {
            assertThat(fixture.storage.read("listings/draft/image", fixture.bytes.length)).isEqualTo(fixture.bytes);
            assertThat(fixture.headCalls.get()).isEqualTo(1);
            assertThat(fixture.getCalls.get()).isEqualTo(1);
            assertThat(fixture.getIfMatch).isEqualTo("\"staging-etag\"");
            assertThat(fixture.headPath).isEqualTo("/staging/listings/draft/image");
            assertThat(fixture.getPath).isEqualTo(fixture.headPath);
        }
    }

    @Test void rejectsDeclaredSizeMismatchBeforeDownloadingObject() throws Exception {
        try (var fixture = new StorageFixture()) {
            failure(RequestFailure.Kind.INVALID_INPUT, () -> fixture.storage.read("listings/draft/image", fixture.bytes.length - 1));
            assertThat(fixture.getCalls.get()).isZero();
        }
    }

    @Test void rejectsOversizedStoredObjectsBeforeDownloadingThem() throws Exception {
        try (var fixture = new StorageFixture()) {
            fixture.headLength = ListingMedia.MAX_BYTES + 1L;
            failure(RequestFailure.Kind.INVALID_INPUT, () -> fixture.storage.read("listings/draft/image", fixture.headLength));
            assertThat(fixture.getCalls.get()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(ints = {404, 412})
    void mapsMissingOrChangedObjectsToConflict(int status) throws Exception {
        try (var fixture = new StorageFixture()) {
            fixture.getStatus = status;
            failure(RequestFailure.Kind.CONFLICT, () -> fixture.storage.read("listings/draft/image", fixture.bytes.length));
            assertThat(fixture.getIfMatch).isEqualTo("\"staging-etag\"");
        }
    }

    @Test void mapsMissingHeadToConflictWithoutTryingToGetTheObject() throws Exception {
        try (var fixture = new StorageFixture()) {
            fixture.headStatus = 404;
            failure(RequestFailure.Kind.CONFLICT, () -> fixture.storage.read("listings/draft/image", fixture.bytes.length));
            assertThat(fixture.getCalls.get()).isZero();
        }
    }

    @ParameterizedTest @CsvSource({"HEAD,500", "GET,503", "PUT,500"})
    void mapsProviderFailuresToSafeUnavailable(String method, int status) throws Exception {
        try (var fixture = new StorageFixture()) {
            if (method.equals("HEAD")) fixture.headStatus = status;
            if (method.equals("GET")) fixture.getStatus = status;
            if (method.equals("PUT")) fixture.putStatus = status;
            failure(RequestFailure.Kind.UNAVAILABLE, () -> {
                if (method.equals("PUT")) fixture.storage.writeFinal("listings/draft/final", fixture.bytes, "image/png");
                else fixture.storage.read("listings/draft/image", fixture.bytes.length);
            });
        }
    }

    @Test void writesImmutableFinalBytesWithPrivateCacheHeaders() throws Exception {
        try (var fixture = new StorageFixture()) {
            fixture.storage.writeFinal("listings/draft/final", fixture.bytes, "image/png");
            assertThat(fixture.putPath).isEqualTo("/final/listings/draft/final");
            assertThat(fixture.putIfNoneMatch).isEqualTo("*");
            assertThat(fixture.putContentType).isEqualTo("image/png");
            assertThat(fixture.putCacheControl).isEqualTo("private, no-store");
            assertThat(fixture.putBody).isEqualTo(fixture.bytes);
        }
    }

    @Test void signsUploadsForTenMinutesAndReadsForFiveMinutes() throws Exception {
        try (var fixture = new StorageFixture()) {
            var upload = fixture.storage.upload("listings/draft/image", "image/png", fixture.bytes.length);
            URI put = URI.create(upload.url());
            assertThat(put.getPath()).isEqualTo("/staging/listings/draft/image");
            assertThat(query(put)).containsEntry("X-Amz-Expires", "600");
            assertThat(upload.headers()).containsEntry("content-type", "image/png").containsEntry("if-none-match", "*");
            assertThat(upload.toString()).isEqualTo("Upload[private]");
            URI get = URI.create(fixture.storage.download("listings/draft/final"));
            assertThat(get.getPath()).isEqualTo("/final/listings/draft/final");
            assertThat(query(get)).containsEntry("X-Amz-Expires", "300").containsEntry("response-cache-control", "private, no-store");
            assertThat(fixture.headCalls.get()).isZero();
            assertThat(fixture.getCalls.get()).isZero();
        }
    }

    @Test void disabledStorageHasNoFallbackAndEveryOperationFailsClosed() {
        try (var storage = new R2Storage(new MockEnvironment())) {
            assertThat(storage.enabled()).isFalse();
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.upload("listings/draft/image", "image/png", 1));
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.read("listings/draft/image", 1));
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.writeFinal("listings/draft/final", new byte[]{1}, "image/png"));
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.download("listings/draft/final"));
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.delete(StorageObjects.Bucket.STAGING, "listings/draft/image"));
            failure(RequestFailure.Kind.UNAVAILABLE, () -> storage.visit(StorageObjects.Bucket.FINAL, ignored -> {}));
        }
    }

    @ParameterizedTest @CsvSource({"R2_STAGING_BUCKET,SANDBOX_R2_STAGING_BUCKET", "R2_STAGING_BUCKET,SANDBOX_R2_FINAL_BUCKET",
            "R2_FINAL_BUCKET,SANDBOX_R2_STAGING_BUCKET", "R2_FINAL_BUCKET,SANDBOX_R2_FINAL_BUCKET"})
    void rejectsEveryPrimaryAndSandboxBucketCollisionEvenWhenStorageIsDisabled(String primary, String sandbox) {
        var settings = new MockEnvironment().withProperty(primary, "shared-private-bucket").withProperty(sandbox, "shared-private-bucket");
        rejected(settings);
    }

    @ParameterizedTest @ValueSource(strings = {"R2_ACCESS_KEY_ID", "R2_SECRET_KEY"})
    void rejectsSharedCredentialsEvenWhenStorageIsDisabled(String key) {
        rejected(new MockEnvironment().withProperty(key, "fabricated-shared-secret").withProperty("SANDBOX_" + key, "fabricated-shared-secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"SANDBOX_R2_ACCESS_KEY_ID", "SANDBOX_R2_SECRET_KEY"})
    void productionRejectsSandboxCredentialsEvenWhenStorageIsDisabled(String key) {
        rejected(new MockEnvironment().withProperty("opennest.deployment.environment", "PRODUCTION").withProperty(key, "fabricated-demo-secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"TRUE", "False", "yes", "1", ""})
    void rejectsNonCanonicalStorageFlags(String flag) { rejected(new MockEnvironment().withProperty("OPENNEST_STORAGE_ENABLED", flag)); }

    @Test void enabledStorageRequiresCompleteSelectedCredentialsAndDistinctPrivateBuckets() {
        rejected(new MockEnvironment().withProperty("OPENNEST_STORAGE_ENABLED", "true"));
        rejected(configured().withProperty("R2_ACCOUNT_ID", "not-an-account"));
        rejected(configured().withProperty("R2_FINAL_BUCKET", "primary-staging"));
        rejected(configured().withProperty("R2_STAGING_BUCKET", "https://invalid.example/bucket"));
        rejected(configured().withProperty("opennest.deployment.data-mode", "SANDBOX"));
        try (var storage = new R2Storage(configured())) { assertThat(storage.enabled()).isTrue(); }
    }

    private static MockEnvironment configured() {
        return new MockEnvironment().withProperty("OPENNEST_STORAGE_ENABLED", "true")
                .withProperty("R2_ACCOUNT_ID", "0123456789abcdef0123456789abcdef")
                .withProperty("R2_ACCESS_KEY_ID", "fabricated-primary-access").withProperty("R2_SECRET_KEY", "fabricated-primary-secret")
                .withProperty("R2_STAGING_BUCKET", "primary-staging").withProperty("R2_FINAL_BUCKET", "primary-final");
    }
    private static void rejected(MockEnvironment settings) {
        assertThatThrownBy(() -> new R2Storage(settings)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid OpenNest storage settings or isolation");
    }
    private static void failure(RequestFailure.Kind kind, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
    private static Map<String, String> query(URI uri) {
        var result = new LinkedHashMap<String, String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class StorageFixture implements AutoCloseable {
        final byte[] bytes = "fabricated image bytes".getBytes(StandardCharsets.UTF_8);
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final R2Storage storage;
        final AtomicInteger headCalls = new AtomicInteger();
        final AtomicInteger getCalls = new AtomicInteger();
        final AtomicInteger sentBytes = new AtomicInteger();
        final CountDownLatch bodyFinished = new CountDownLatch(1);
        volatile int headStatus = 200, getStatus = 200, putStatus = 200;
        volatile Duration headDelay = Duration.ZERO, chunkDelay = Duration.ZERO;
        volatile long headLength = bytes.length;
        volatile String headPath, getPath, getIfMatch, putPath, putIfNoneMatch, putContentType, putCacheControl;
        volatile byte[] putBody;

        StorageFixture() throws IOException {
            this(Duration.ofSeconds(20));
        }

        StorageFixture(Duration deadline) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor); server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("fabricated-access", "fabricated-secret"));
            var configuration = S3Configuration.builder().chunkedEncodingEnabled(false).pathStyleAccessEnabled(true).build();
            var client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto")).credentialsProvider(credentials)
                    .serviceConfiguration(configuration).overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(5))
                            .apiCallAttemptTimeout(Duration.ofSeconds(3)).retryPolicy(RetryPolicy.none())).build();
            var signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.of("auto")).credentialsProvider(credentials)
                    .serviceConfiguration(configuration).build();
            storage = new R2Storage(client, signer, "staging", "final", deadline);
        }

        void handle(HttpExchange request) throws IOException {
            try {
                String method = request.getRequestMethod();
                int status = method.equals("HEAD") ? headStatus : method.equals("GET") ? getStatus : putStatus;
                if (method.equals("HEAD")) {
                    headCalls.incrementAndGet(); headPath = request.getRequestURI().getPath();
                    pause(headDelay);
                    request.getResponseHeaders().set("ETag", "\"staging-etag\"");
                    request.getResponseHeaders().set("Content-Length", status == 200 ? Long.toString(headLength) : "0");
                    request.sendResponseHeaders(status, -1); return;
                }
                if (method.equals("GET")) {
                    getCalls.incrementAndGet(); getPath = request.getRequestURI().getPath();
                    getIfMatch = request.getRequestHeaders().getFirst("If-Match");
                    if (status == 200) {
                        request.getResponseHeaders().set("ETag", "\"staging-etag\"");
                        request.sendResponseHeaders(200, bytes.length);
                        if (chunkDelay.isZero()) request.getResponseBody().write(bytes);
                        else for (byte value : bytes) {
                            request.getResponseBody().write(value); request.getResponseBody().flush(); sentBytes.incrementAndGet(); pause(chunkDelay);
                        }
                        return;
                    }
                }
                if (method.equals("PUT")) {
                    putPath = request.getRequestURI().getPath(); putIfNoneMatch = request.getRequestHeaders().getFirst("If-None-Match");
                    putContentType = request.getRequestHeaders().getFirst("Content-Type"); putCacheControl = request.getRequestHeaders().getFirst("Cache-Control");
                    putBody = request.getRequestBody().readAllBytes();
                    if (status == 200) { request.getResponseHeaders().set("ETag", "\"final-etag\""); request.sendResponseHeaders(200, -1); return; }
                }
                byte[] error = ("<Error><Code>" + (status == 412 ? "PreconditionFailed" : status == 404 ? "NoSuchKey" : "ServiceUnavailable")
                        + "</Code><Message>Controlled test failure</Message></Error>").getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/xml");
                request.sendResponseHeaders(status, error.length); request.getResponseBody().write(error);
            } finally {
                if (request.getRequestMethod().equals("GET")) bodyFinished.countDown();
                request.close();
            }
        }
        private static void pause(Duration duration) throws IOException {
            try { Thread.sleep(duration); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Controlled HTTP fixture interrupted"); }
        }
        @Override public void close() { storage.close(); server.stop(0); executor.shutdownNow(); }
    }
}

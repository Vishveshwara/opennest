package in.opennest.listing.internal;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import in.opennest.common.RequestFailure;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
class R2Storage implements StorageObjects, AutoCloseable {
    private final S3Client client;
    private final S3Presigner signer;
    private final String staging;
    private final String finals;

    R2Storage(S3Client client, S3Presigner signer, String staging, String finals) {
        this.client = client; this.signer = signer; this.staging = staging; this.finals = finals;
    }

    @org.springframework.beans.factory.annotation.Autowired
    R2Storage(Environment environment) {
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
                .serviceConfiguration(configuration).overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
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
        try {
            var head = client.headObject(builder -> builder.bucket(staging).key(key));
            if (head.contentLength() != expectedSize || head.contentLength() > ListingMedia.MAX_BYTES) throw ListingInput.invalid();
            try (var stream = client.getObject(builder -> builder.bucket(staging).key(key).ifMatch(head.eTag()))) {
                var bytes = stream.readNBytes(ListingMedia.MAX_BYTES + 1);
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
}

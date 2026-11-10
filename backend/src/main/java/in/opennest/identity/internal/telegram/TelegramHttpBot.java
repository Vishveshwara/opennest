package in.opennest.identity.internal.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import in.opennest.common.RequestFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramHttpBot implements TelegramBot, AutoCloseable {
    private final TelegramSettings settings;
    private final HttpClient http;
    private final URI endpoint;
    private final java.util.function.LongSupplier nanoTime;
    private final java.util.concurrent.atomic.AtomicLong retryAt;
    private final JsonMapper json = new JsonMapper();
    @org.springframework.beans.factory.annotation.Autowired
    TelegramHttpBot(TelegramSettings settings) {
        this(settings, URI.create("https://api.telegram.org/"), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    TelegramHttpBot(TelegramSettings settings, URI endpoint, HttpClient http) { this(settings, endpoint, http, System::nanoTime); }
    TelegramHttpBot(TelegramSettings settings, URI endpoint, HttpClient http, java.util.function.LongSupplier nanoTime) {
        this.settings = settings; this.endpoint = endpoint; this.http = http; this.nanoTime = nanoTime;
        this.retryAt = new java.util.concurrent.atomic.AtomicLong(nanoTime.getAsLong());
    }
    @Override public void contact(long chat) {
        call("sendMessage", Map.of("chat_id", chat, "text", "OpenNest sign-in. Share your own +91 Telegram contact, then enter the code only in the browser where you started. Sharing contact alone does not sign you in.",
                "reply_markup", Map.of("keyboard", List.of(List.of(Map.of("text", "Share my contact", "request_contact", true))), "resize_keyboard", true, "one_time_keyboard", true)), Duration.ofSeconds(5));
    }
    @Override public void code(long chat, String code) {
        call("sendMessage", Map.of("chat_id", chat, "text", "OpenNest sign-in code: " + code + ". Enter it only in the browser where you started. Expires with your five-minute challenge. Do not share this code.",
                "protect_content", true, "reply_markup", Map.of("remove_keyboard", true)), Duration.ofSeconds(5));
    }
    @Override public void checkPolling() {
        var bot = call("getMe", Map.of(), Duration.ofSeconds(5));
        var hook = call("getWebhookInfo", Map.of(), Duration.ofSeconds(5));
        if (!bot.path("username").isString() || !bot.path("username").asString().equalsIgnoreCase(settings.username())
                || !bot.path("is_bot").isBoolean() || !bot.path("is_bot").asBoolean()
                || !hook.path("url").isString() || !hook.path("url").asString().isEmpty()) throw unavailable();
    }
    @Override public JsonNode updates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "limit", 1, "timeout", 10, "allowed_updates", List.of("message")), Duration.ofSeconds(15));
    }
    private JsonNode call(String method, Object body, Duration timeout) {
        if (nanoTime.getAsLong() - retryAt.get() < 0) throw unavailable();
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve("./bot" + settings.token() + "/" + method)).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            pending = http.sendAsync(request, ignored -> new LimitedBody());
            var response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            var result = json.readTree(response.body());
            if (response.statusCode() == 429) {
                long seconds = Math.clamp(result.path("parameters").path("retry_after").asLong(1), 1, 3600);
                retryAt.set(nanoTime.getAsLong() + Duration.ofSeconds(seconds).toNanos());
            }
            if (response.statusCode() != 200 || !result.path("ok").asBoolean(false) || !result.has("result")) throw unavailable();
            return result.get("result");
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException failure) {
            if (pending != null) pending.cancel(true); throw unavailable();
        } catch (RuntimeException failure) { throw unavailable(); }
        catch (InterruptedException failure) { if (pending != null) pending.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
    }
    private static RequestFailure unavailable() { return new RequestFailure(RequestFailure.Kind.UNAVAILABLE); }
    @Override public void close() { http.close(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 65536) {
                    subscription.cancel(); result.completeExceptionally(new java.io.IOException("OpenNest provider response exceeds limit")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

package in.opennest.identity.internal.telegram;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TelegramHttpBotTests {
    private static final String TOKEN = "123456789:fabricated_primary_bot_token_canary";
    private static final String CODE = "004217";
    private static final String PRIVATE_URL = "https://private.invalid/fabricated-contact";
    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void requestsOnlyTheSendersOwnContactWithAOneTimeKeyboard() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.bot.contact(123456789L);
            var request = fixture.take();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/bot" + TOKEN + "/sendMessage");
            assertThat(request.contentType()).isEqualTo("application/json");
            assertThat(request.body()).isEqualTo(JSON.readTree("""
                    {"chat_id":123456789,
                     "text":"OpenNest sign-in. Share your own +91 Telegram contact, then enter the code only in the browser where you started. Sharing contact alone does not sign you in.",
                     "reply_markup":{"keyboard":[[{"text":"Share my contact","request_contact":true}]],
                                     "resize_keyboard":true,"one_time_keyboard":true}}
                    """));
        }
    }

    @Test
    void sendsTheExactCodeAsProtectedContentAndRemovesTheKeyboard() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.bot.code(123456789L, CODE);
            var request = fixture.take();
            assertThat(request.path()).isEqualTo("/bot" + TOKEN + "/sendMessage");
            assertThat(request.body()).isEqualTo(JSON.readTree("""
                    {"chat_id":123456789,
                     "text":"OpenNest sign-in code: 004217. Enter it only in the browser where you started. Expires with your five-minute challenge. Do not share this code.",
                     "protect_content":true,"reply_markup":{"remove_keyboard":true}}
                    """));
        }
    }

    @Test
    void checksBotIdentityAndAnEmptyWebhookBeforePolling() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getMe", 200, "{\"ok\":true,\"result\":{\"username\":\"opennestprimarybot\",\"is_bot\":true}}");
            fixture.reply("getWebhookInfo", 200, "{\"ok\":true,\"result\":{\"url\":\"\"}}");
            fixture.bot.checkPolling();
            assertThat(fixture.take().path()).isEqualTo("/bot" + TOKEN + "/getMe");
            var webhook = fixture.take();
            assertThat(webhook.path()).isEqualTo("/bot" + TOKEN + "/getWebhookInfo");
            assertThat(webhook.body()).isEqualTo(JSON.readTree("{}"));
        }
    }

    @Test
    void rejectsABotWhoseUsernameDoesNotMatchConfiguration() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getMe", 200, "{\"ok\":true,\"result\":{\"username\":\"OtherFabricatedBot\",\"is_bot\":true}}");
            fixture.reply("getWebhookInfo", 200, "{\"ok\":true,\"result\":{\"url\":\"\"}}");
            unavailable(fixture.bot::checkPolling);
        }
    }

    @Test
    void rejectsPollingWhenTheBotHasAnExistingWebhook() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getMe", 200, "{\"ok\":true,\"result\":{\"username\":\"OpenNestPrimaryBot\",\"is_bot\":true}}");
            fixture.reply("getWebhookInfo", 200, "{\"ok\":true,\"result\":{\"url\":\"" + PRIVATE_URL + "\"}}");
            unavailable(fixture.bot::checkPolling);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"username\":123,\"is_bot\":true}",
            "{\"username\":\"OpenNestPrimaryBot\"}", "{\"username\":\"OpenNestPrimaryBot\",\"is_bot\":false}",
            "{\"username\":\"OpenNestPrimaryBot\",\"is_bot\":\"true\"}", "{\"username\":\"OpenNestPrimaryBot\",\"is_bot\":1}"})
    void rejectsMissingMalformedOrNonBotIdentityResults(String result) throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getMe", 200, "{\"ok\":true,\"result\":" + result + "}");
            fixture.reply("getWebhookInfo", 200, "{\"ok\":true,\"result\":{\"url\":\"\"}}");
            unavailable(fixture.bot::checkPolling);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"url\":null}", "{\"url\":0}",
            "{\"url\":false}", "{\"url\":[]}", "{\"url\":{}}"})
    void rejectsMissingOrNonTextualWebhookUrls(String result) throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getMe", 200, "{\"ok\":true,\"result\":{\"username\":\"OpenNestPrimaryBot\",\"is_bot\":true}}");
            fixture.reply("getWebhookInfo", 200, "{\"ok\":true,\"result\":" + result + "}");
            unavailable(fixture.bot::checkPolling);
        }
    }

    @Test
    void pollsOneMessageAtTheDurableOffsetAndReturnsProviderUpdates() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("getUpdates", 200, "{\"ok\":true,\"result\":[{\"update_id\":42,\"message\":{\"text\":\"fixture\"}}]}");
            assertThat(fixture.bot.updates(42)).isEqualTo(JSON.readTree("[{\"update_id\":42,\"message\":{\"text\":\"fixture\"}}]"));
            var request = fixture.take();
            assertThat(request.path()).isEqualTo("/bot" + TOKEN + "/getUpdates");
            assertThat(request.body()).isEqualTo(JSON.readTree("""
                    {"offset":42,"limit":1,"timeout":10,"allowed_updates":["message"]}
                    """));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{\"ok\":true}", "{\"ok\":false,\"result\":{}}", "{\"ok\":true,\"result\":"})
    void rejectsMalformedOrUnsuccessfulProviderResponsesWithoutLeakingDetails(String body) throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("sendMessage", 200, body);
            unavailable(() -> fixture.bot.code(123456789L, CODE));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 500, 503})
    void mapsProviderOutagesToAnOpaqueUnavailableFailure(int status) throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("sendMessage", status, "{\"ok\":false,\"description\":\"" + TOKEN + " " + CODE + " " + PRIVATE_URL + "\"}");
            unavailable(() -> fixture.bot.code(123456789L, CODE));
        }
    }

    @Test
    void rejectsAProviderBodyBeyondTheResponseByteLimit() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("sendMessage", 200, "{\"ok\":true,\"result\":\"" + "x".repeat(65536) + "\"}");
            unavailable(() -> fixture.bot.code(123456789L, CODE));
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 60, Long.MAX_VALUE})
    void honorsProviderRetryAfterWithoutIssuingAnotherRequest(long seconds) throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.reply("sendMessage", 429, "{\"ok\":false,\"parameters\":{\"retry_after\":" + seconds + "}}");
            unavailable(() -> fixture.bot.code(123456789L, CODE));
            unavailable(() -> fixture.bot.contact(123456789L));
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void hidesConnectionErrorsIncludingTheTokenBearingRequestUrl() throws Exception {
        try (var fixture = new BotFixture()) {
            fixture.server.stop(0);
            unavailable(() -> fixture.bot.code(123456789L, CODE));
            assertThat(fixture.requests).isEmpty();
        }
    }

    private static void unavailable(Runnable action) {
        var failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(RequestFailure.class).hasMessage("OpenNest request failed").hasNoCause();
        assertThat(((RequestFailure) failure).kind()).isEqualTo(RequestFailure.Kind.UNAVAILABLE);
        assertThat(failure.toString()).doesNotContain(TOKEN, CODE, PRIVATE_URL, "bot" + TOKEN);
        assertThat(failure.getStackTrace()).isEmpty();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private record Recorded(String method, String path, String contentType, JsonNode body) {}
    private record Reply(int status, String body) {}

    private static final class BotFixture implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final BlockingQueue<Recorded> requests = new LinkedBlockingQueue<>();
        private final Map<String, Reply> replies = new ConcurrentHashMap<>();
        private final TelegramHttpBot bot;

        private BotFixture() throws IOException {
            server.createContext("/", this::serve);
            server.setExecutor(executor);
            server.start();
            var settings = new TelegramSettings(new MockEnvironment()
                    .withProperty("opennest.identity.telegram-enabled", "true")
                    .withProperty("opennest.identity.google-enabled", "true")
                    .withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                    .withProperty("TELEGRAM_BOT_USERNAME", "OpenNestPrimaryBot")
                    .withProperty("TELEGRAM_BOT_TOKEN", TOKEN)
                    .withProperty("TELEGRAM_OTP_HMAC_KEY", "fabricated_primary_otp_hmac_key_canary"));
            bot = new TelegramHttpBot(settings, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        }

        private void reply(String method, int status, String body) { replies.put(method, new Reply(status, body)); }

        private Recorded take() throws InterruptedException {
            var request = requests.poll(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            return request;
        }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                requests.add(new Recorded(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Content-Type"),
                        JSON.readTree(exchange.getRequestBody().readAllBytes())));
                var reply = replies.getOrDefault(path.substring(path.lastIndexOf('/') + 1), new Reply(200, "{\"ok\":true,\"result\":{}}"));
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
            bot.close();
        }
    }
}

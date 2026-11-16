package in.opennest.identity.internal.telegram;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpExchange;
import in.opennest.OpenNestApplication;
import in.opennest.listing.internal.FrontendStorageFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class FrontendBrowserHarness {
    private final String primaryUi = "http://localhost:" + Integer.getInteger("opennest.frontend.primary-port", 3000);
    private final String sandboxUi = "http://localhost:" + Integer.getInteger("opennest.frontend.sandbox-port", 3001);
    private final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private final List<PostgreSQLContainer> databases = new ArrayList<>();
    private final List<GenericContainer<?>> stores = new ArrayList<>();
    private final List<String> origins = new ArrayList<>();
    private final Map<String, Long> senders = new ConcurrentHashMap<>();
    private final AtomicLong updates = new AtomicLong(100);
    private final AtomicLong chats = new AtomicLong(6_000_000_000L);
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String controlSecret = UUID.randomUUID().toString();

    @BeforeAll void start() throws Exception {
        for (int index = 0; index < 2; index++) {
            var database = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("opennest_frontend_" + index).withUsername("frontend_" + index).withPassword("fabricated-browser-db-" + index);
            databases.add(database); database.start();
            var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "fabricated-browser-cache-" + index);
            stores.add(redis); redis.start();
            try (var socket = new ServerSocket(0)) { origins.add("http://localhost:" + socket.getLocalPort()); }
        }
        for (int index = 0; index < 2; index++) {
            var settings = settings(index);
            apps.add(new SpringApplication(OpenNestApplication.class, BotConfiguration.class, FrontendStorageFixture.class).run(settings.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new)));
        }
        apps.getFirst().getBean(FrontendStorageFixture.BrowserStorage.class).server().createContext("/telegram", this::telegram);
        apps.getFirst().getBean(FrontendStorageFixture.BrowserStorage.class).server().createContext("/reset", exchange -> {
            try (exchange) {
                String supplied = exchange.getRequestHeaders().getFirst("X-Control-Secret");
                if (!exchange.getRequestMethod().equals("POST") || supplied == null
                        || !MessageDigest.isEqual(controlSecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                    reply(exchange, 403, Map.of("status", "DENIED")); return;
                }
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(apps.get(1).getBean("sandboxOperations"), "reset", true);
                reply(exchange, 200, Map.of("status", "RESET"));
            }
        });
    }

    @AfterAll void close() {
        apps.reversed().forEach(ConfigurableApplicationContext::close);
        stores.reversed().forEach(GenericContainer::close);
        databases.reversed().forEach(PostgreSQLContainer::close);
    }

    @Test void runBrowserAcceptanceAgainstRealIsolatedStores() throws Exception {
        Path frontend = Path.of(System.getProperty("user.dir")).resolveSibling("frontend");
        if (!java.nio.file.Files.exists(frontend.resolve("package.json"))) frontend = Path.of(System.getProperty("user.dir"), "frontend");
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        var command = windows ? List.of("cmd.exe", "/d", "/c", "npm", "run", "test:e2e", "--", "--project=chromium")
                : List.of("npm", "run", "test:e2e", "--", "--project=chromium");
        var builder = new ProcessBuilder(command).directory(frontend.toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.of("OPENNEST_E2E_REAL", "1", "E2E_PRIMARY_API", origins.getFirst(),
                "E2E_SANDBOX_API", origins.get(1), "E2E_CONTROL_ORIGIN", apps.getFirst().getBean(FrontendStorageFixture.BrowserStorage.class).origin(),
                "E2E_CONTROL_SECRET", controlSecret));
        builder.environment().putAll(Map.of("E2E_PRIMARY_UI", primaryUi, "E2E_SANDBOX_UI", sandboxUi));
        Process process = builder.start();
        var output = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var pump = output.submit(() -> process.inputReader().lines().forEach(System.out::println));
            assertThat(process.waitFor(8, TimeUnit.MINUTES)).as("Browser acceptance finishes within eight minutes").isTrue();
            pump.get(10, TimeUnit.SECONDS);
            assertThat(process.exitValue()).as("Real-store browser acceptance exit status").isZero();
        } finally { if (process.isAlive()) process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); output.shutdownNow(); }
    }

    private void telegram(HttpExchange exchange) throws IOException {
        try (exchange) {
            String supplied = exchange.getRequestHeaders().getFirst("X-Control-Secret");
            if (!exchange.getRequestMethod().equals("POST") || supplied == null
                    || !MessageDigest.isEqual(controlSecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 403, Map.of("status", "DENIED")); return;
            }
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                if (bytes.length > 8192) { reply(exchange, 413, Map.of("status", "REJECTED")); return; }
                var body = json.readTree(bytes);
                String number = body.path("phoneNumber").asString("");
                var link = URI.create(body.path("botUrl").asString(""));
                String query = link.getRawQuery();
                if (!number.matches("\\+910[0-9]{9}") || !"t.me".equals(link.getHost()) || query == null || !query.startsWith("start=")) {
                    reply(exchange, 400, Map.of("status", "REJECTED")); return;
                }
                String token = URLDecoder.decode(query.substring("start=".length()), StandardCharsets.UTF_8);
                long chat = senders.computeIfAbsent(number, ignored -> chats.incrementAndGet());
                webhook(event(chat, Map.of("text", "/start " + token)));
                Thread.sleep(1050);
                webhook(event(chat, Map.of("contact", Map.of("user_id", chat, "phone_number", number, "first_name", "Fabricated"))));
                String code = apps.getFirst().getBean(ControlledTelegram.class).codes.remove(chat);
                if (code == null) { reply(exchange, 409, Map.of("status", "NOT_READY")); return; }
                reply(exchange, 200, Map.of("code", code));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); reply(exchange, 503, Map.of("status", "UNAVAILABLE"));
            } catch (Exception failure) { reply(exchange, 500, Map.of("status", "FAILED")); }
        }
    }

    private void webhook(JsonNode update) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(origins.getFirst() + "/api/v1/webhooks/telegram"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Telegram-Bot-Api-Secret-Token", apps.getFirst().getBean(TelegramSettings.class).webhookSecret())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(update))).build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) throw new IllegalStateException("Controlled browser webhook failed");
    }

    private JsonNode event(long chat, Map<String, Object> content) {
        var message = new LinkedHashMap<>(content);
        message.putAll(Map.of("date", Instant.now().getEpochSecond(), "chat", Map.of("id", chat, "type", "private"),
                "from", Map.of("id", chat, "is_bot", false)));
        return json.readTree(json.writeValueAsString(Map.of("update_id", updates.incrementAndGet(), "message", message)));
    }

    private void reply(HttpExchange exchange, int status, Map<String, String> body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private Map<String, String> settings(int target) {
        var result = new LinkedHashMap<String, String>();
        result.putAll(Map.of("server.port", URI.create(origins.get(target)).getPort() + "", "server.address", "127.0.0.1",
                "OPENNEST_ENVIRONMENT", "DEVELOPMENT", "OPENNEST_DATA_MODE", target == 0 ? "PRIMARY" : "SANDBOX",
                "OPENNEST_BACKEND_ORIGIN", origins.getFirst(), "OPENNEST_FRONTEND_ORIGIN", primaryUi,
                "SANDBOX_BACKEND_ORIGIN", origins.get(1), "SANDBOX_FRONTEND_ORIGIN", sandboxUi));
        for (int index = 0; index < 2; index++) {
            String prefix = index == 0 ? "" : "SANDBOX_";
            var database = databases.get(index); var redis = stores.get(index);
            result.put(prefix + "DB_HOST", database.getHost()); result.put(prefix + "DB_PORT", database.getMappedPort(5432).toString());
            result.put(prefix + "DB_NAME", database.getDatabaseName()); result.put(prefix + "DB_USER", database.getUsername());
            result.put(prefix + "DB_PASSWORD", database.getPassword()); result.put(prefix + "REDIS_HOST", redis.getHost());
            result.put(prefix + "REDIS_PORT", redis.getMappedPort(6379).toString()); result.put(prefix + "REDIS_PASSWORD", "fabricated-browser-cache-" + index);
        }
        result.put("OPENNEST_TELEGRAM_AUTH_ENABLED", target == 0 ? "true" : "false");
        result.put("OPENNEST_TELEGRAM_POLLING_ENABLED", "false"); result.put("OPENNEST_STORAGE_ENABLED", "false");
        result.put("OPENNEST_PERSONA_SWITCHING", target == 1 ? "true" : "false");
        result.put("OPENNEST_DEMO_RESET", target == 1 ? "true" : "false"); result.put("OPENNEST_DEMO_TELEMETRY", "false");
        if (target == 0) {
            result.put("TELEGRAM_BOT_USERNAME", "OpenNestBrowserTestBot"); result.put("TELEGRAM_BOT_TOKEN", "1234567:AAAAAAAAAAAAAAAAAAAAAAAAAA");
            result.put("TELEGRAM_WEBHOOK_SECRET", "fabricated-browser-webhook-secret-at-least-32");
            result.put("TELEGRAM_OTP_HMAC_KEY", "fabricated-browser-hmac-secret-at-least-32");
        }
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BotConfiguration {
        @Bean @Primary ControlledTelegram frontendControlledTelegram() { return new ControlledTelegram(); }
    }

    static final class ControlledTelegram implements TelegramBot {
        private final Map<Long, String> codes = new ConcurrentHashMap<>();
        public void hint(long chat, TelegramBot.Hint hint) {}
        public void contact(long chat) {}
        public void code(long chat, String code) { codes.put(chat, code); }
        public void checkPolling() {}
        public JsonNode updates(long offset) { return new JsonMapper().createArrayNode(); }
    }
}

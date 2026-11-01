package in.opennest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import in.opennest.identity.IdentityAccounts;
import in.opennest.identity.internal.configuration.IdentitySettings;
import in.opennest.identity.internal.persistence.AccountStore;
import in.opennest.identity.internal.security.SessionPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class IdentityAuthenticationIT {
    private static ControlledOidcProvider provider;
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<PostgreSQLContainer> databases = new ArrayList<>();
    private final List<GenericContainer<?>> redisServers = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = new JsonMapper();

    @BeforeAll void start() throws Exception {
        provider = new ControlledOidcProvider();
        for (int i = 0; i < 2; i++) {
            var database = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("opennest_identity_" + i).withUsername("identity_" + i).withPassword("fabricated-db-secret-" + i);
            database.start();
            databases.add(database);
            var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "fabricated-redis-secret-" + i)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
            redis.start();
            redisServers.add(redis);
        }
        for (int i = 0; i < 2; i++) {
            var settings = settings(i);
            contexts.add(new SpringApplication(OpenNestApplication.class, ProviderConfiguration.class)
                    .run(settings.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new)));
            contexts.get(i).getBean(com.zaxxer.hikari.HikariDataSource.class).setConnectionTimeout(1000);
        }
    }

    @AfterAll void close() {
        contexts.reversed().forEach(ConfigurableApplicationContext::close);
        redisServers.reversed().forEach(GenericContainer::close);
        databases.reversed().forEach(PostgreSQLContainer::close);
        if (provider != null) provider.close();
    }

    @Test void loginRotatesSessionReturnsPrivateProfileAndLogoutRevokesIt(CapturedOutput output) throws Exception {
        var browser = new Browser(0);
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        var before = json.readTree(browser.get("/api/v1/auth/csrf").body());
        String oldCookie = browser.cookie;
        var callback = browser.authorize("valid", "first-user");
        var loggedIn = browser.get(callback);
        assertThat(loggedIn.statusCode()).isEqualTo(302);
        assertThat(loggedIn.headers().firstValue("Location")).contains("https://opennest.example.invalid/");
        assertThat(browser.cookie).isNotEqualTo(oldCookie);
        assertThat(loggedIn.headers().allValues("Set-Cookie").toString()).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
        var profile = browser.get("/api/v1/users/me");
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode account = json.readTree(profile.body());
        assertThat(account.get("phoneNumber").isNull()).isTrue();
        assertThat(account.get("phoneVerified").asBoolean()).isFalse();
        assertThat(account.get("averageRating").isNull()).isTrue();
        assertThat(account.get("trustBadge").asString()).isEqualTo("STANDARD");
        assertThat(contexts.getFirst().getBean(IdentityAccounts.class).hasVerifiedContact(UUID.fromString(account.get("id").asString()))).isFalse();
        var replay = new Browser(0);
        replay.cookie = oldCookie;
        assertThat(replay.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        Session session = repository(0).findById(browser.sessionId());
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        SecurityContext stored = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(stored.getAuthentication().getPrincipal()).isInstanceOf(SessionPrincipal.class);
        assertThat(session.getAttributeNames()).doesNotContain("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(browser.post("/api/v1/auth/logout", null).statusCode()).isEqualTo(403);
        assertThat(browser.post("/api/v1/auth/logout", "invalid").statusCode()).isEqualTo(403);
        assertThat(browser.post("/api/v1/auth/logout", before.get("token").asString()).statusCode()).isEqualTo(403);
        var csrf = json.readTree(browser.get("/api/v1/auth/csrf").body());
        assertThat(csrf.get("token").asString()).isNotEqualTo(before.get("token").asString());
        assertThat(csrf.get("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        String loggedInCookie = browser.cookie;
        var logout = browser.post("/api/v1/auth/logout", csrf.get("token").asString());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
        replay.cookie = loggedInCookie;
        assertThat(replay.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        assertThat(profile.body()).doesNotContain("fabricated-access-token-canary", "issuer", "subject", "email");
        assertThat(output).doesNotContain(ControlledOidcProvider.SECRET, ControlledOidcProvider.EMAIL,
                "fabricated-access-token-canary", oldCookie, loggedInCookie, "code_verifier=");
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "signature", "expired-token", "nonce", "missing-nonce", "pkce", "expired-code", "denied"})
    void invalidProviderResponsesCannotCreateAccounts(String scenario) throws Exception {
        long before = count(0, "users");
        var browser = new Browser(0);
        var failed = browser.get(browser.authorize(scenario, UUID.randomUUID().toString()));
        assertThat(failed.statusCode()).isEqualTo(302);
        assertThat(failed.headers().firstValue("Location")).contains("https://opennest.example.invalid/?login=failed");
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        assertThat(count(0, "users")).isEqualTo(before);
    }

    @Test void stateIsBrowserBoundAndCallbacksCannotBeReplayed() throws Exception {
        var browser = new Browser(0);
        String callback = browser.authorize("valid", "state-user");
        int exchanges = provider.exchanges.get();
        var other = new Browser(0);
        assertThat(other.get(callback).headers().firstValue("Location")).contains("https://opennest.example.invalid/?login=failed");
        assertThat(provider.exchanges.get()).isEqualTo(exchanges);
        assertThat(browser.get(callback.replaceFirst("state=[^&]+", "state=wrong")).headers().firstValue("Location"))
                .contains("https://opennest.example.invalid/?login=failed");
        assertThat(provider.exchanges.get()).isEqualTo(exchanges);
        browser = new Browser(0);
        callback = browser.authorize("valid", "replay-user");
        assertThat(browser.get(callback).headers().firstValue("Location")).contains("https://opennest.example.invalid/");
        assertThat(browser.get(callback).headers().firstValue("Location")).contains("https://opennest.example.invalid/?login=failed");
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(200);
        assertThat(browser.get("/login/oauth2/code/google?code=bogus&state=bogus").headers().firstValue("Location"))
                .contains("https://opennest.example.invalid/?login=failed");
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(200);
    }

    @Test void repeatedSubjectsReuseAccountsWhileSameEmailsStaySeparate() throws Exception {
        var first = login(0, "repeat-subject");
        String id = json.readTree(first.get("/api/v1/users/me").body()).get("id").asString();
        var repeated = login(0, "repeat-subject");
        assertThat(json.readTree(repeated.get("/api/v1/users/me").body()).get("id").asString()).isEqualTo(id);
        var unrelated = login(0, "different-subject-same-email");
        assertThat(json.readTree(unrelated.get("/api/v1/users/me").body()).get("id").asString()).isNotEqualTo(id);
    }

    @Test void verifiedContactIsPrivateToItsAccountAndUsesTheExistingSchemaContract() throws Exception {
        var owner = login(0, "verified-contact-owner");
        UUID ownerId = UUID.fromString(json.readTree(owner.get("/api/v1/users/me").body()).get("id").asString());
        String fabricatedContact = "+910000000000";
        var database = new JdbcTemplate(contexts.getFirst().getBean(javax.sql.DataSource.class));
        database.update("update users set phone_number=?, phone_verified_at=now() where id=?", fabricatedContact, ownerId);
        assertThat(contexts.getFirst().getBean(IdentityAccounts.class).hasVerifiedContact(ownerId)).isTrue();
        assertThat(contexts.getFirst().getBean(IdentityAccounts.class).hasVerifiedContact(UUID.randomUUID())).isFalse();
        var profile = json.readTree(owner.get("/api/v1/users/me").body());
        assertThat(profile.get("phoneVerified").asBoolean()).isTrue();
        assertThat(profile.get("phoneNumber").asString()).isEqualTo(fabricatedContact);
        var other = login(0, "contact-seeking-account");
        var otherProfile = other.get("/api/v1/users/me?userId=" + ownerId);
        assertThat(otherProfile.statusCode()).isEqualTo(200);
        assertThat(otherProfile.body()).doesNotContain(fabricatedContact, ownerId.toString());
        assertThat(json.readTree(otherProfile.body()).get("phoneVerified").asBoolean()).isFalse();
        assertThat(other.get("/api/v1/users/" + ownerId).statusCode()).isEqualTo(403);
        assertThat(new Browser(0).get("/api/v1/users/me?userId=" + ownerId).statusCode()).isEqualTo(401);
    }

    @Test void concurrentSignupCreatesExactlyOneUserAndIdentity() throws Exception {
        var accounts = contexts.getFirst().getBean(AccountStore.class);
        long usersBefore = count(0, "users");
        long identitiesBefore = count(0, "auth_identities");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                start.await();
                return accounts.login(provider.origin(), "concurrent-subject", "Fabricated user", ControlledOidcProvider.EMAIL, true);
            }));
            start.countDown();
            var ids = new java.util.HashSet<UUID>();
            for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(count(0, "users")).isEqualTo(usersBefore + 1);
        assertThat(count(0, "auth_identities")).isEqualTo(identitiesBefore + 1);
    }

    @Test void expiredIdleAndAbsoluteSessionsRequireLogin() throws Exception {
        var idle = login(0, "idle-user");
        update(repository(0), idle.sessionId(), session -> session.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(31))));
        assertThat(idle.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        var absolute = login(0, "absolute-user");
        String absoluteId = absolute.sessionId();
        update(repository(0), absoluteId, session -> {
            SecurityContext security = session.getAttribute("SPRING_SECURITY_CONTEXT");
            var principal = (SessionPrincipal) security.getAuthentication().getPrincipal();
            session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                    new SessionPrincipal(principal.userId(), principal.realm(), Instant.now().minus(Duration.ofHours(24))),
                    null, security.getAuthentication().getAuthorities())));
        });
        assertThat(absolute.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        assertThat(repository(0).findById(absoluteId)).isNull();
    }

    @Test void sandboxSessionsAndHeadersNeverGrantPrimaryAccess() throws Exception {
        var sandbox = login(1, "sandbox-user");
        var primary = new Browser(0);
        primary.cookie = sandbox.cookie;
        assertThat(primary.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        primary.cookie = sandbox.cookie.replace("OPENNEST_SANDBOX_SESSION", "__Host-OPENNEST_SESSION");
        assertThat(primary.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        Session foreign = repository(1).findById(sandbox.sessionId());
        Session copied = copy(repository(0), foreign);
        primary.cookie = "__Host-OPENNEST_SESSION=" + Base64.getEncoder().encodeToString(copied.getId().getBytes(StandardCharsets.UTF_8));
        assertThat(primary.get("/api/v1/users/me").statusCode()).isEqualTo(401);
        assertThat(repository(0).findById(copied.getId())).isNull();
        var response = http.send(HttpRequest.newBuilder(URI.create(primary.origin + "/api/v1/users/me"))
                .header("X-Sandbox-Mode", "SANDBOX").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test void originsAreExactAndUntrustedReturnUrlsAreIgnored() throws Exception {
        var browser = new Browser(0);
        assertThat(browser.get("/login").statusCode()).isEqualTo(401);
        var anonymous = http.send(HttpRequest.newBuilder(URI.create(browser.origin + "/api/v1/users/me"))
                .header("Origin", "https://opennest.example.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
        assertThat(anonymous.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        var loggedIn = login(0, "cors-csrf-user");
        var rejectedLogout = http.send(HttpRequest.newBuilder(URI.create(loggedIn.origin + "/api/v1/auth/logout"))
                .header("Origin", "https://opennest.example.invalid").header("Cookie", loggedIn.cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rejectedLogout.statusCode()).isEqualTo(403);
        assertThat(rejectedLogout.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
        assertThat(rejectedLogout.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        var response = browser.get("/oauth2/authorization/google?returnUrl=https://evil.example.invalid");
        var parameters = ControlledOidcProvider.parameters(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
        assertThat(parameters.get("redirect_uri")).isEqualTo("https://api.opennest.example.invalid/login/oauth2/code/google");
        assertThat(parameters.get("scope").split(" ")).containsExactlyInAnyOrder("openid", "email", "profile");
        for (String origin : List.of("https://opennest.example.invalid", "https://evil.example.invalid")) {
            response = http.send(HttpRequest.newBuilder(URI.create(browser.origin + "/api/v1/auth/logout"))
                    .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-CSRF-TOKEN").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.contains("evil") ? 403 : 200);
            if (origin.contains("evil")) assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
            if (!origin.contains("evil")) {
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
            }
        }
    }

    @Test @Order(Integer.MAX_VALUE - 1) void databaseFailureDoesNotLeaveAnAuthenticatedSession() throws Exception {
        var browser = new Browser(0);
        var callback = browser.authorize("valid", "database-down-user");
        databases.getFirst().stop();
        var response = browser.get(callback);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("The service is temporarily unavailable").doesNotContain("database-down-user");
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(401);
    }

    @Test @Order(Integer.MAX_VALUE) void redisFailureFailsClosedBeforeAndAfterAuthentication() throws Exception {
        var browser = login(1, "redis-outage-user");
        redisServers.get(1).stop();
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(503);
        assertThat(new Browser(1).get("/api/v1/auth/csrf").statusCode()).isEqualTo(503);
    }

    private Browser login(int target, String subject) throws Exception {
        var browser = new Browser(target);
        var response = browser.get(browser.authorize("valid", subject));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).doesNotContain("failed");
        assertThat(browser.get("/api/v1/users/me").statusCode()).isEqualTo(200);
        return browser;
    }
    private RedisSessionRepository repository(int target) { return contexts.get(target).getBean(RedisSessionRepository.class); }
    private <S extends Session> void update(SessionRepository<S> repository, String id, java.util.function.Consumer<Session> change) {
        S session = repository.findById(id);
        change.accept(session);
        repository.save(session);
    }
    private <S extends Session> Session copy(SessionRepository<S> repository, Session source) {
        S session = repository.createSession();
        for (String name : source.getAttributeNames()) session.setAttribute(name, source.getAttribute(name));
        repository.save(session);
        return session;
    }
    private long count(int target, String table) {
        return new JdbcTemplate(contexts.get(target).getBean(javax.sql.DataSource.class)).queryForObject("select count(*) from " + table, Long.class);
    }
    private Map<String, String> settings(int target) {
        var settings = new LinkedHashMap<String, String>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("spring.main.banner-mode", "off");
        settings.put("OPENNEST_ENVIRONMENT", target == 0 ? "PRODUCTION" : "DEVELOPMENT");
        settings.put("OPENNEST_DATA_MODE", target == 0 ? "PRIMARY" : "SANDBOX");
        settings.put("OPENNEST_GOOGLE_ENABLED", "true");
        settings.put("OPENNEST_BACKEND_ORIGIN", "https://api.opennest.example.invalid");
        settings.put("OPENNEST_FRONTEND_ORIGIN", "https://opennest.example.invalid");
        settings.put("SANDBOX_BACKEND_ORIGIN", "https://sandbox-api.opennest.example.invalid");
        settings.put("SANDBOX_FRONTEND_ORIGIN", "https://sandbox.opennest.example.invalid");
        for (String flag : List.of("OPENNEST_DEVELOPMENT_AUTH", "OPENNEST_PERSONA_SWITCHING", "OPENNEST_DEMO_RESET", "OPENNEST_DEMO_TELEMETRY")) settings.put(flag, "false");
        for (int i = 0; i < 2; i++) {
            String prefix = i == 0 ? "" : "SANDBOX_";
            settings.put(prefix + "GOOGLE_CLIENT_ID", "fabricated-google-client-" + i);
            settings.put(prefix + "GOOGLE_CLIENT_SECRET", target == i ? ControlledOidcProvider.SECRET : "");
            settings.put(prefix + "DB_HOST", databases.get(i).getHost());
            settings.put(prefix + "DB_PORT", databases.get(i).getMappedPort(5432).toString());
            settings.put(prefix + "DB_NAME", databases.get(i).getDatabaseName());
            settings.put(prefix + "DB_USER", databases.get(i).getUsername());
            settings.put(prefix + "DB_PASSWORD", target == i ? databases.get(i).getPassword() : "");
            settings.put(prefix + "REDIS_HOST", redisServers.get(i).getHost());
            settings.put(prefix + "REDIS_PORT", redisServers.get(i).getMappedPort(6379).toString());
            settings.put(prefix + "REDIS_USER", "");
            settings.put(prefix + "REDIS_PASSWORD", target == i ? "fabricated-redis-secret-" + i : "");
        }
        return settings;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary ClientRegistrationRepository controlledClients(Environment environment) {
            var google = IdentitySettings.read(environment).selected(environment);
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId(google.clientId()).clientSecret(google.clientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid", "email", "profile")
                    .redirectUri(google.backendOrigin() + "/login/oauth2/code/google")
                    .authorizationUri(provider.origin() + "/authorize").tokenUri(provider.origin() + "/token")
                    .jwkSetUri(provider.origin() + "/jwks").issuerUri(provider.origin()).userNameAttributeName("sub").build());
        }
        @Bean LettuceClientConfigurationBuilderCustomizer identityTestTimeouts() {
            return builder -> builder.commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO);
        }
    }

    private class Browser {
        final String origin;
        String cookie = "";
        Browser(int target) { origin = "http://127.0.0.1:" + contexts.get(target).getEnvironment().getProperty("local.server.port"); }
        HttpResponse<String> get(String path) throws Exception { return send(path, null, false); }
        HttpResponse<String> post(String path, String csrf) throws Exception { return send(path, csrf, true); }
        HttpResponse<String> send(String path, String csrf, boolean post) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(45));
            if (!cookie.isEmpty()) request.header("Cookie", cookie);
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (post) request.POST(HttpRequest.BodyPublishers.noBody()); else request.GET();
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String setCookie : response.headers().allValues("Set-Cookie")) cookie = setCookie.split(";", 2)[0];
            return response;
        }
        String authorize(String scenario, String subject) throws Exception {
            var entry = get("/oauth2/authorization/google");
            assertThat(entry.statusCode()).isEqualTo(302);
            String location = entry.headers().firstValue("Location").orElseThrow();
            var response = http.send(HttpRequest.newBuilder(URI.create(location + "&scenario=" + scenario + "&subject="
                    + ControlledOidcProvider.encode(subject))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            URI callback = URI.create(response.headers().firstValue("Location").orElseThrow());
            return callback.getRawPath() + "?" + callback.getRawQuery();
        }
        String sessionId() { return new String(Base64.getDecoder().decode(cookie.split("=", 2)[1]), StandardCharsets.UTF_8); }
    }
}

package in.opennest.interaction.internal;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import in.opennest.OpenNestApplication;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import in.opennest.identity.TrustBadge;
import in.opennest.identity.internal.security.SessionPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class Phase4IT {
    private final List<PostgreSQLContainer> databases = new ArrayList<>();
    private final List<GenericContainer<?>> caches = new ArrayList<>();
    private final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Starts independent real PostGIS/Redis pairs and applications, closing partial fixtures on startup failure.
     */
    @BeforeAll void start() {
        try {
            for (int i = 0; i < 2; i++) {
                var db = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("opennest_phase4_" + i).withUsername("phase4_" + i).withPassword("fabricated-phase4-db-" + i);
                db.start(); databases.add(db);
                var cache = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                        .withCommand("redis-server", "--requirepass", "fabricated-phase4-cache-" + i);
                cache.start(); caches.add(cache);
            }
            for (int i = 0; i < 2; i++) apps.add(new SpringApplication(OpenNestApplication.class, ClockConfiguration.class)
                    .run(settings(i).entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new)));
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    @AfterAll void close() {
        apps.reversed().forEach(ConfigurableApplicationContext::close);
        caches.reversed().forEach(GenericContainer::close);
        databases.reversed().forEach(PostgreSQLContainer::close);
    }
    @BeforeEach void freshSandbox() { reset(true); }

    /**
     * Proves competing reveals admit exactly ten listings and retries stay free after Redis state is cleared.
     */
    @Test void concurrentDistinctRevealsPermitTenAndRetriesRemainFreeAfterRedisLoss() throws Exception {
        UUID seeker = user(false), owner = user(true);
        var listings = new ArrayList<UUID>();
        for (int i = 0; i < 11; i++) listings.add(property(owner, true));
        var outcomes = race(listings.stream().<Callable<Boolean>>map(id -> () -> {
            try { as(seeker, () -> service().reveal(id, null)); return true; }
            catch (RequestFailure failure) { assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.RATE_LIMITED); return false; }
        }).toList());
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(10);
        UUID repeated = jdbc(0).queryForObject("select property_id from contact_reveals where tenant_id = ? limit 1", UUID.class, seeker);
        assertThat(race(java.util.stream.IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> {
            assertThat(as(seeker, () -> service().reveal(repeated, null))).containsEntry("remainingReveals", 0L); return true;
        }).toList())).containsOnly(true);
        apps.getFirst().getBean(org.springframework.data.redis.core.StringRedisTemplate.class).getConnectionFactory().getConnection().serverCommands().flushDb();
        assertThat(as(seeker, () -> service().reveal(repeated, null))).containsEntry("remainingReveals", 0L);
        assertThat(jdbc(0).queryForObject("select count(*) from contact_reveals where tenant_id = ?", Long.class, seeker)).isEqualTo(10);
    }

    @Test void contactFailuresDoNotConsumeSlotsOrExposePrivateData() {
        UUID seeker = user(false), unverified = user(false), owner = user(true);
        UUID id = property(owner, true), paused = property(owner, false), missingContact = property(unverified, true);
        failure(RequestFailure.Kind.UNAUTHENTICATED, () -> service().reveal(id, null));
        failure(RequestFailure.Kind.FORBIDDEN, () -> as(owner, () -> service().reveal(id, null)));
        failure(RequestFailure.Kind.NOT_FOUND, () -> as(seeker, () -> service().reveal(paused, null)));
        failure(RequestFailure.Kind.CONTACT_VERIFICATION_REQUIRED, () -> as(seeker, () -> service().reveal(missingContact, null)));
        assertThat(jdbc(0).queryForObject("select count(*) from contact_reveals where tenant_id = ?", Long.class, seeker)).isZero();
        var disclosed = as(seeker, () -> service().reveal(id, null));
        assertThat(disclosed.get("phoneNumber")).isEqualTo(jdbc(0).queryForObject("select phone_number from users where id = ?", String.class, owner));
        assertThat(disclosed.get("whatsappUrl")).isEqualTo("https://wa.me/" + disclosed.get("phoneNumber").toString().substring(1));
        assertThat(jdbc(0).queryForMap("select * from contact_reveals where tenant_id = ?", seeker)).doesNotContainKeys("phone_number", "phoneNumber");
    }

    @Test void quotaClockIsCapturedAfterWaitingAcrossKolkataMidnight() throws Exception {
        UUID seeker = user(false), id = property(user(true), true);
        var clock = apps.getFirst().getBean(ClockJdbc.class);
        Instant before = Instant.parse("2026-11-10T18:29:59Z"), after = before.plusSeconds(2);
        clock.now.set(before);
        try (var connection = databases.getFirst().createConnection(""); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from users where id = ? for update")) {
                statement.setObject(1, seeker); statement.execute();
            }
            var captured = new CountDownLatch(1); clock.captured.set(captured);
            var attempt = executor.submit(() -> as(seeker, () -> service().reveal(id, null)));
            assertThat(captured.await(250, TimeUnit.MILLISECONDS)).isFalse();
            clock.now.set(after); connection.commit();
            assertThat(attempt.get(10, TimeUnit.SECONDS)).containsEntry("revealedDate", LocalDate.of(2026, 11, 11));
            assertThat(jdbc(0).queryForObject("select created_at from contact_reveals where tenant_id = ?", Timestamp.class, seeker).toInstant()).isEqualTo(after);
            clock.now.set(before);
            assertThat(as(seeker, () -> service().reveal(id, null))).containsEntry("revealedDate", LocalDate.of(2026, 11, 10));
            assertThat(jdbc(0).queryForObject("select count(*) from contact_reveals where tenant_id = ?", Long.class, seeker)).isEqualTo(2);
        } finally { clock.now.set(null); clock.captured.set(null); }
    }

    @Test void historicalReviewConversionPreservesIdentityTextAndCounts() {
        UUID owner = user(true), seeker = user(false), other = user(false), id = property(owner, true);
        history(seeker, id, owner); history(other, id, owner);
        var review = as(seeker, () -> service().writeReview(id, node("{\"rating\":5,\"issueTag\":\"GENUINE_OWNER\",\"commentText\":\"Original fabricated comment\"}"), false));
        failure(RequestFailure.Kind.CONFLICT, () -> as(seeker, () -> service().writeReview(id, node("{\"rating\":4,\"issueTag\":\"OTHER\"}"), false)));
        var report = as(seeker, () -> service().writeReview(id, null, true));
        assertThat(report).containsEntry("id", review.get("id")).containsEntry("createdAt", review.get("createdAt"))
                .containsEntry("commentText", "Original fabricated comment").containsEntry("isAutoBrokerReport", true).containsEntry("allegationNotice", InteractionStore.ALLEGATION);
        assertThat(as(seeker, () -> service().writeReview(id, null, true))).isEqualTo(report);
        as(other, () -> service().writeReview(id, node("{\"rating\":5,\"issueTag\":\"OTHER\"}"), false));
        assertAggregates(owner, 2, 1);
        assertThat(jdbc(0).queryForObject("select status from properties where id = ?", String.class, id)).isEqualTo("ACTIVE");
        jdbc(0).update("update properties set status = 'PAUSED' where id = ?", id);
        assertThat(as(seeker, () -> service().state(id))).containsEntry("hasHistoricalReveal", true);
        failure(RequestFailure.Kind.NOT_FOUND, () -> service().reviews(id, new LinkedMultiValueMap<>()));
    }

    /**
     * Races review creation and repeated reports, then checks one row per pair and exact owner aggregates.
     */
    @Test void concurrentReportsAndReviewsKeepOneRowAndExactOwnerAggregates() throws Exception {
        UUID owner = user(true), id = property(owner, true);
        var seekers = java.util.stream.IntStream.range(0, 10).mapToObj(i -> user(false)).toList();
        seekers.forEach(seeker -> history(seeker, id, owner));
        var operations = new ArrayList<Callable<Boolean>>();
        for (UUID seeker : seekers) {
            operations.add(() -> { as(seeker, () -> service().writeReview(id, null, true)); return true; });
            operations.add(() -> { as(seeker, () -> service().writeReview(id, null, true)); return true; });
            operations.add(() -> {
                try { as(seeker, () -> service().writeReview(id, node("{\"rating\":5,\"issueTag\":\"OTHER\"}"), false)); }
                catch (RequestFailure failure) { assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONFLICT); }
                return true;
            });
        }
        assertThat(race(operations)).containsOnly(true);
        assertAggregates(owner, 10, 10);
        assertThat(jdbc(0).queryForObject("select count(*) from owner_reviews where property_id = ?", Long.class, id)).isEqualTo(10);
    }

    @Test void aggregateFailureRollsBackTheReviewAndUnauthorizedReviewsFailClosed() {
        UUID owner = user(true), seeker = user(false), id = property(owner, true);
        failure(RequestFailure.Kind.FORBIDDEN, () -> as(seeker, () -> service().writeReview(id, null, true)));
        failure(RequestFailure.Kind.FORBIDDEN, () -> as(owner, () -> service().writeReview(id, null, true)));
        history(seeker, id, owner);
        jdbc(0).execute("alter table users add constraint phase4_rollback_check check (id <> '" + owner + "'::uuid or review_count = 0)");
        try {
            assertThatThrownBy(() -> as(seeker, () -> service().writeReview(id, null, true))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc(0).queryForObject("select count(*) from owner_reviews where property_id = ?", Long.class, id)).isZero();
            assertAggregates(owner, 0, 0);
        } finally { jdbc(0).execute("alter table users drop constraint phase4_rollback_check"); }
    }

    @Test void reciprocalOwnersCanRevealAndReportWithoutForeignKeyDeadlocks() throws Exception {
        UUID first = user(true), second = user(true), firstListing = property(first, true), secondListing = property(second, true);
        assertThat(race(List.of(
                () -> { as(first, () -> service().reveal(secondListing, null)); return true; },
                () -> { as(second, () -> service().reveal(firstListing, null)); return true; }))).containsOnly(true);
        assertThat(race(List.of(
                () -> { as(first, () -> service().writeReview(secondListing, null, true)); return true; },
                () -> { as(second, () -> service().writeReview(firstListing, null, true)); return true; }))).containsOnly(true);
        assertAggregates(first, 1, 1); assertAggregates(second, 1, 1);
    }

    /**
     * Proves a snapshot captured before reset cannot authorize a subsequent sandbox operation.
     */
    @Test void oldRepeatableReadSnapshotCannotPassGenerationCheckAfterReset() {
        var transaction = new TransactionTemplate(apps.get(1).getBean(PlatformTransactionManager.class));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc(1).queryForObject("select generation from sandbox_state", Long.class);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> { reset(true); return true; }).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            apps.get(1).getBean(SandboxBoundary.class).lock();
            return null;
        })).isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.UNAVAILABLE));
    }

    @Test void publicReviewsPaginateAndCallerStateNeverIncludesContactsOrOtherReviewers() throws Exception {
        UUID owner = user(true), seeker = user(false), stranger = user(false), id = property(owner, true);
        history(seeker, id, owner);
        as(seeker, () -> service().writeReview(id, null, true));
        var page = new LinkedMultiValueMap<String, String>(); page.add("page", "3"); page.add("size", "1");
        assertThat(service().reviews(id, page)).containsEntry("items", List.of()).containsEntry("totalElements", 1L);
        var state = as(stranger, () -> service().state(id));
        assertThat(state).containsEntry("review", null).containsEntry("hasHistoricalReveal", false).doesNotContainKeys("phoneNumber", "reviewerId", "ownerId");
        var publicResult = send(0, "GET", "/api/v1/interactions/listings/" + id + "/reviews", null, null, null);
        assertThat(publicResult.statusCode()).isEqualTo(200);
        assertThat(publicResult.body()).doesNotContain(owner.toString(), seeker.toString(), "phoneNumber", "privateAddress");
        assertThat(publicResult.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(send(0, "GET", "/api/v1/interactions/listings/" + id + "/state", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send(0, "GET", "/api/v1/interactions/listings/" + id + "/reviews?sort=rating", null, null, null).statusCode()).isEqualTo(400);
    }

    @Test void authenticatedHttpRequiresCsrfAndStrictBoundedRequests() throws Exception {
        UUID owner = user(true), seeker = user(false), id = property(owner, true);
        String cookie = cookie(seeker);
        String path = "/api/v1/interactions/listings/" + id;
        assertThat(send(0, "POST", path + "/reveal-phone", "{}", cookie, null).statusCode()).isEqualTo(403);
        String csrf = node(send(0, "GET", "/api/v1/auth/csrf", null, cookie, null).body()).get("token").stringValue();
        assertThat(send(0, "POST", path + "/reveal-phone", "{}", cookie, csrf).statusCode()).isEqualTo(200);
        assertThat(send(0, "POST", path + "/reviews", "{\"rating\":5,\"rating\":1,\"issueTag\":\"OTHER\"}", cookie, csrf).statusCode()).isEqualTo(400);
        assertThat(send(0, "POST", path + "/reviews", "{\"rating\":5,\"issueTag\":\"ASKED_BROKERAGE\"}", cookie, csrf).statusCode()).isEqualTo(400);
        assertThat(send(0, "POST", path + "/reviews", "{\"rating\":5,\"issueTag\":\"OTHER\",\"commentText\":\"" + "x".repeat(9000) + "\"}", cookie, csrf).statusCode()).isEqualTo(413);
        assertThat(send(0, "POST", path + "/reviews", "{\"rating\":5,\"issueTag\":\"OTHER\"}", cookie, csrf).statusCode()).isEqualTo(200);
        assertThat(send(0, "POST", path + "/report-brokerage", "{}", cookie, csrf).statusCode()).isEqualTo(200);
    }

    @Test void sandboxFixturesCoverCitiesModesBadgesAndNeverContainCallableContacts() throws Exception {
        assertThat(jdbc(1).queryForList("select distinct city from properties", String.class)).hasSizeGreaterThanOrEqualTo(5);
        assertThat(jdbc(1).queryForList("select distinct coalesce(flat_rental_mode, category) from properties", String.class)).containsExactlyInAnyOrder("ENTIRE_FLAT", "ROOM", "PG_HOSTEL");
        assertThat(jdbc(1).queryForObject("select count(*) from users where phone_number is not null", Long.class)).isZero();
        assertThat(jdbc(1).queryForObject("select count(*) from auth_identities", Long.class)).isZero();
        var badges = jdbc(1).query("select average_rating, review_count, broker_report_count from users", (row, index) -> TrustBadge.calculate(row.getBigDecimal(1), row.getLong(2), row.getLong(3)));
        assertThat(new HashSet<>(badges)).containsExactlyInAnyOrder(TrustBadge.values());
        var image = send(1, "GET", "/api/v1/sandbox/images/ROOM.png", null, null, null);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("content-type")).contains("image/png");
        assertThat(send(0, "GET", "/api/v1/sandbox/personas", null, null, null).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(send(1, "POST", "/api/v1/sandbox/reset", "{}", null, null).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(send(1, "GET", "/api/v1/sandbox/telemetry", null, null, null).statusCode()).isGreaterThanOrEqualTo(400);
    }

    @Test void sandboxSelectionRotatesSessionAndSupportsNormalReviewRulesButNoContactBypass() throws Exception {
        var browser = new Browser(1);
        browser.csrf(); String before = browser.cookie;
        assertThat(browser.call("POST", "/api/v1/sandbox/personas/select", "{\"personaKey\":\"seeker-new\"}").statusCode()).isEqualTo(200);
        assertThat(browser.cookie).isNotEqualTo(before);
        UUID listing = jdbc(1).queryForObject("select id from properties where status = 'ACTIVE' order by id limit 1", UUID.class);
        String path = "/api/v1/interactions/listings/" + listing;
        assertThat(browser.call("POST", path + "/report-brokerage", "{}").statusCode()).isEqualTo(403);
        browser.csrf();
        assertThat(browser.call("POST", path + "/reveal-phone", "{}").statusCode()).isEqualTo(403);
        assertThat(browser.call("POST", path + "/report-brokerage", "{}").statusCode()).isEqualTo(200);
        assertThat(node(browser.call("GET", "/api/v1/users/me", null).body()).get("phoneVerified").booleanValue()).isFalse();
        assertThat(send(0, "GET", "/api/v1/users/me", null, browser.cookie, null).statusCode()).isEqualTo(401);
        assertThat(browser.call("POST", "/api/v1/sandbox/personas/select", "{\"personaKey\":\"unknown\"}").statusCode()).isEqualTo(400);
        assertThat(browser.call("POST", "/api/v1/sandbox/personas/select", "{\"personaKey\":\"owner-standard\"}").statusCode()).isEqualTo(200);
        browser.csrf();
        UUID paused = jdbc(1).queryForObject("select id from properties where status = 'PAUSED'", UUID.class);
        assertThat(browser.call("PATCH", "/api/v1/listings/" + paused + "/inventory", "{\"version\":0,\"maleRoomsAvailable\":1,\"femaleRoomsAvailable\":0,\"sharedRoomsAvailable\":0,\"maxSharingPerRoom\":2}").statusCode()).isEqualTo(200);
        assertThat(browser.call("POST", "/api/v1/listings/" + paused + "/publish", "{\"version\":1}").statusCode()).isEqualTo(403);
    }

    /**
     * Checks reset waits for active writes, expires old sessions, preserves primary data and deduplicates scheduled intervals.
     */
    @Test void resetWaitsForWritesInvalidatesOldSessionsAndDoesNotTouchPrimary() throws Exception {
        var browser = new Browser(1); browser.csrf();
        browser.call("POST", "/api/v1/sandbox/personas/select", "{\"personaKey\":\"seeker-new\"}"); browser.csrf();
        long primaryUsers = jdbc(0).queryForObject("select count(*) from users", Long.class);
        long generation = jdbc(1).queryForObject("select generation from sandbox_state", Long.class);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writer = executor.submit(() -> new TransactionTemplate(apps.get(1).getBean(PlatformTransactionManager.class)).execute(status -> {
                apps.get(1).getBean(SandboxBoundary.class).lock(); acquired.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return null;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() -> { reset(true); return true; });
            try {
                assertThatThrownBy(() -> reset.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(jdbc(1).queryForObject("select generation from sandbox_state", Long.class)).isEqualTo(generation);
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS); reset.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc(1).queryForObject("select generation from sandbox_state", Long.class)).isEqualTo(generation + 1);
        assertThat(browser.call("GET", "/api/v1/users/me", null).statusCode()).isEqualTo(401);
        assertThat(jdbc(0).queryForObject("select count(*) from users", Long.class)).isEqualTo(primaryUsers);
        reset(false); reset(false);
        assertThat(jdbc(1).queryForObject("select generation from sandbox_state", Long.class)).isEqualTo(generation + 1);
        assertThat(jdbc(1).queryForObject("select count(*) from owner_reviews", Long.class)).isEqualTo(10);
    }

    /**
     * Runs both operator commands without HTTP and verifies context shutdown, reset generation and safe telemetry fields.
     */
    @Test void operatorCommandsRunWithoutHttpAndExitWithOnlyAggregateTelemetry() {
        long generation = jdbc(1).queryForObject("select generation from sandbox_state", Long.class);
        for (String command : List.of("telemetry", "reset")) {
            var configuration = settings(1);
            configuration.put("OPENNEST_PERSONA_SWITCHING", "false");
            configuration.put("spring.main.web-application-type", "none");
            configuration.put("opennest.sandbox.command", command);
            try (var context = new SpringApplication(OpenNestApplication.class).run(configuration.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))) {
                assertThat(context.isActive()).isFalse();
            }
        }
        assertThat(jdbc(1).queryForObject("select generation from sandbox_state", Long.class)).isEqualTo(generation + 1);
        Map<String, Object> telemetry = ReflectionTestUtils.invokeMethod(apps.get(1).getBean("sandboxOperations"), "telemetry");
        assertThat(telemetry).containsOnlyKeys("users", "properties", "property_images", "contact_reveals", "owner_reviews", "initialized", "seedVersion", "generation", "resetAt");
    }

    @Test void failedResetRollsBackFixturesAndGenerationTogether() {
        long generation = jdbc(1).queryForObject("select generation from sandbox_state", Long.class);
        jdbc(1).execute("alter table properties add constraint phase4_seed_failure check (monthly_rent <> 25000) not valid");
        try {
            assertThatThrownBy(() -> reset(true)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc(1).queryForObject("select generation from sandbox_state", Long.class)).isEqualTo(generation);
            assertThat(jdbc(1).queryForObject("select count(*) from properties", Long.class)).isEqualTo(17);
            assertThat(jdbc(1).queryForObject("select count(*) from owner_reviews", Long.class)).isEqualTo(10);
        } finally { jdbc(1).execute("alter table properties drop constraint phase4_seed_failure"); }
    }

    @Test void transactionBoundaryRejectsARequestWhoseSessionPassedTheFilterBeforeReset() {
        long generation = jdbc(1).queryForObject("select generation from sandbox_state", Long.class);
        reset(true);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.getSession().setAttribute(SandboxBoundary.SESSION_GENERATION, generation);
        var attributes = new org.springframework.web.context.request.ServletRequestAttributes(request);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
        try {
            var transaction = new TransactionTemplate(apps.get(1).getBean(PlatformTransactionManager.class));
            failure(RequestFailure.Kind.UNAUTHENTICATED, () -> transaction.execute(status -> {
                apps.get(1).getBean(SandboxBoundary.class).lock();
                throw new AssertionError("A stale request must fail before it can mutate fixtures");
            }));
        } finally {
            attributes.requestCompleted();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    private void reset(boolean force) { ReflectionTestUtils.invokeMethod(apps.get(1).getBean("sandboxOperations"), "reset", force); }
    private JdbcTemplate jdbc(int target) { return apps.get(target).getBean(JdbcTemplate.class); }
    private InteractionService service() { return apps.getFirst().getBean(InteractionService.class); }
    private JsonNode node(String body) { return json.readTree(body); }
    /**
     * Creates a fabricated account and optionally assigns a synthetic non-callable contact for reveal tests.
     */
    private UUID user(boolean verified) {
        UUID id = UUID.randomUUID();
        jdbc(0).update("insert into users (id, full_name) values (?, 'Fabricated Phase 4 account')", id);
        if (verified) jdbc(0).update("update users set phone_number = ?, phone_verified_at = current_timestamp where id = ?",
                "+910" + String.format("%09d", jdbc(0).queryForObject("select count(*) from users", Long.class)), id);
        return id;
    }
    /**
     * Creates a fabricated entire-flat listing with the requested publication visibility for interaction tests.
     */
    private UUID property(UUID owner, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc(0).update("insert into properties (id, owner_id, title, description, category, flat_rental_mode, status, city, locality, private_address, latitude, longitude, monthly_rent, security_deposit, bedrooms, whole_flat_available, max_occupants) values (?,?,'Fabricated Phase 4 listing','Fabricated test listing','FLAT','ENTIRE_FLAT',?,'pune','test locality','Private fabricated address',18.52,73.86,25000,50000,2,true,4)", id, owner, published ? "ACTIVE" : "PAUSED");
        return id;
    }
    private void history(UUID seeker, UUID listing, UUID owner) {
        jdbc(0).update("insert into contact_reveals (id, tenant_id, property_id, owner_id, created_at) values (?,?,?,?,current_timestamp - interval '3 days')", UUID.randomUUID(), seeker, listing, owner);
    }
    private void assertAggregates(UUID owner, long reviews, long reports) {
        var result = jdbc(0).queryForMap("select review_count, broker_report_count, average_rating from users where id = ?", owner);
        assertThat(result).containsEntry("review_count", reviews).containsEntry("broker_report_count", reports);
        assertThat(result.get("average_rating")).isEqualTo(jdbc(0).queryForObject("select avg(rating) from owner_reviews where owner_id = ?", java.math.BigDecimal.class, owner));
    }
    private <T> T as(UUID id, Supplier<T> work) {
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(id, "development:primary", Instant.now()), null, List.of())));
        try { return work.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
    private void failure(RequestFailure.Kind kind, Supplier<?> work) {
        assertThatThrownBy(work::get).isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
    /**
     * Releases coordinated virtual-thread tasks together and returns their results within bounded deadlines.
     */
    private List<Boolean> race(List<Callable<Boolean>> work) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            for (var task : work) futures.add(executor.submit(() -> { start.await(); return task.call(); }));
            start.countDown(); var results = new ArrayList<Boolean>();
            for (var task : futures) results.add(task.get(60, TimeUnit.SECONDS));
            return results;
        }
    }
    private String cookie(UUID id) {
        return cookie(apps.getFirst().getBean(RedisSessionRepository.class), id);
    }
    private <S extends org.springframework.session.Session> String cookie(org.springframework.session.SessionRepository<S> repository, UUID id) {
        S session = repository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(id, "development:primary", Instant.now()), null, List.of())));
        repository.save(session);
        return "OPENNEST_SESSION=" + Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /**
     * Sends a bounded test HTTP request with optional body, session cookie and CSRF token.
     */
    private HttpResponse<String> send(int target, String method, String path, String body, String cookie, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apps.get(target).getEnvironment().getProperty("local.server.port") + path)).timeout(Duration.ofSeconds(20));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private class Browser {
        final int target; String cookie; String csrf;
        Browser(int target) { this.target = target; }
        HttpResponse<String> call(String method, String path, String body) throws Exception {
            var result = send(target, method, path, body, cookie, csrf);
            result.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("OPENNEST_SANDBOX_SESSION="))
                    .findFirst().ifPresent(value -> cookie = value.split(";", 2)[0]);
            return result;
        }
        void csrf() throws Exception { csrf = node(call("GET", "/api/v1/auth/csrf", null).body()).get("token").stringValue(); }
    }
    /**
     * Builds explicit fabricated store, provider and feature settings for the selected isolated application.
     */
    private Map<String, String> settings(int target) {
        var result = new LinkedHashMap<String, String>();
        result.putAll(Map.of("server.port", "0", "server.address", "127.0.0.1", "OPENNEST_ENVIRONMENT", "DEVELOPMENT",
                "OPENNEST_DATA_MODE", target == 0 ? "PRIMARY" : "SANDBOX", "OPENNEST_TELEGRAM_AUTH_ENABLED", target == 0 ? "true" : "false"));
        result.put("OPENNEST_PERSONA_SWITCHING", target == 1 ? "true" : "false");
        result.put("OPENNEST_DEMO_RESET", target == 1 ? "true" : "false");
        result.put("OPENNEST_DEMO_TELEMETRY", target == 1 ? "true" : "false");
        result.put("TELEGRAM_BOT_USERNAME", "OpenNestFixtureBot"); result.put("TELEGRAM_BOT_TOKEN", "1234567:AAAAAAAAAAAAAAAAAAAAAAAAAA");
        result.put("TELEGRAM_OTP_HMAC_KEY", "fabricated-phase4-hmac-secret-at-least-32"); result.put("TELEGRAM_WEBHOOK_SECRET", "fabricated-phase4-webhook-secret-at-least-32");
        for (int i = 0; i < 2; i++) {
            String prefix = i == 0 ? "" : "SANDBOX_";
            result.put(prefix + "DB_HOST", databases.get(i).getHost()); result.put(prefix + "DB_PORT", databases.get(i).getMappedPort(5432).toString());
            result.put(prefix + "DB_NAME", databases.get(i).getDatabaseName()); result.put(prefix + "DB_USER", databases.get(i).getUsername());
            result.put(prefix + "DB_PASSWORD", target == i ? databases.get(i).getPassword() : "");
            result.put(prefix + "REDIS_HOST", caches.get(i).getHost()); result.put(prefix + "REDIS_PORT", caches.get(i).getMappedPort(6379).toString());
            result.put(prefix + "REDIS_PASSWORD", target == i ? "fabricated-phase4-cache-" + i : "");
        }
        return result;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary ClockJdbc clockJdbc(javax.sql.DataSource dataSource) { return new ClockJdbc(dataSource); }
    }
    static class ClockJdbc extends JdbcTemplate {
        final AtomicReference<Instant> now = new AtomicReference<>();
        final AtomicReference<CountDownLatch> captured = new AtomicReference<>();
        ClockJdbc(javax.sql.DataSource dataSource) { super(dataSource); }
        /**
         * Substitutes only the controlled quota-clock query and delegates every other query to the real database.
         */
        @Override public <T> T queryForObject(String sql, RowMapper<T> mapper) {
            if (sql.equals("select clock_timestamp()") && now.get() != null) {
                Instant instant = now.get();
                if (captured.get() != null) captured.get().countDown();
                return (T) instant;
            }
            return super.queryForObject(sql, mapper);
        }
    }
}

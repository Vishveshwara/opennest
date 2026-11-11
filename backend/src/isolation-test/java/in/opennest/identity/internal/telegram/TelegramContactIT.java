package in.opennest.identity.internal.telegram;

import java.net.URI;
import java.net.http.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import in.opennest.OpenNestApplication;
import in.opennest.common.RequestFailure;
import in.opennest.identity.IdentityAccounts;
import in.opennest.identity.internal.security.SessionPrincipal;
import in.opennest.listing.internal.TelegramListingStorageFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class TelegramContactIT {
    private static final String LOGIN = "/api/v1/auth/telegram/challenges";
    private static final String ISSUER = "https://telegram.org";
    private final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private final List<PostgreSQLContainer> databases = new ArrayList<>();
    private final List<GenericContainer<?>> stores = new ArrayList<>();
    private final JsonMapper json = new JsonMapper();
    private final AtomicLong updates = new AtomicLong(100);
    private final AtomicLong chats = new AtomicLong(5_000_000_000L);
    private final AtomicInteger phones = new AtomicInteger();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll void start() {
        for (int index = 0; index < 2; index++) {
            var db = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("opennest_telegram_" + index).withUsername("telegram_" + index).withPassword("fabricated-telegram-db-" + index);
            db.start(); databases.add(db);
            var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "fabricated-telegram-cache-" + index);
            redis.start(); stores.add(redis);
            var settings = settings(index);
            apps.add(new SpringApplication(OpenNestApplication.class, BotConfiguration.class, TelegramListingStorageFixture.class).run(settings.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new)));
            if (index == 0) apps.getLast().getBean(com.zaxxer.hikari.HikariDataSource.class).setConnectionTimeout(1000);
        }
    }

    @AfterAll void close() {
        apps.reversed().forEach(ConfigurableApplicationContext::close);
        stores.reversed().forEach(GenericContainer::close);
        databases.reversed().forEach(PostgreSQLContainer::close);
    }

    @Test void anonymousBrowserCompletesLoginAndPublicationRetainsImageChecks() throws Exception {
        var browser = new Browser(0);
        String number = phone();
        assertThat(browser.send("GET", "/api/v1/users/me", null, false).statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", LOGIN, json.writeValueAsString(Map.of("phoneNumber", number)), false).statusCode()).isEqualTo(403);
        var created = browser.send("POST", LOGIN, json.writeValueAsString(Map.of("phoneNumber", number)), true);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).doesNotContain("otp", "phoneNumber", "code");
        var challenge = json.readTree(created.body());
        UUID id = UUID.fromString(challenge.get("challengeId").asString());
        long chat = chats.incrementAndGet();
        clearBudget(chat);
        assertThat(webhook(0, start(chat, challenge.get("botUrl").asString().split("start=")[1]), true).statusCode()).isEqualTo(204);
        assertThat(phoneOwners(number)).isZero();
        clearBudget(chat);
        assertThat(webhook(0, contact(chat, number), true).statusCode()).isEqualTo(204);
        String code = bot().codes.get(chat);
        assertThat(code).matches("[0-9]{6}");
        assertThat(challenges().redis.<String, String>opsForHash().entries(challenges().key(id)).values()).doesNotContain(code);
        assertThat(phoneOwners(number)).isZero();
        assertThat(identityCount(chat)).isZero();
        assertThat(browser.send("POST", "/api/v1/listings", pgDraft(), true).statusCode()).isEqualTo(401);
        String path = LOGIN + "/" + id + "/complete";
        assertThat(browser.send("POST", path, json.writeValueAsString(Map.of("code", code)), false).statusCode()).isEqualTo(403);
        var completed = browser.send("POST", path, json.writeValueAsString(Map.of("code", code)), true);
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(completed.body()).get("status").asString()).isEqualTo("AUTHENTICATED");
        browser.refreshCsrf();
        assertThat(browser.send("POST", path, json.writeValueAsString(Map.of("code", code)), true).statusCode()).isEqualTo(200);
        UUID account = browser.account();
        assertThat(jdbc(0).queryForObject("select count(*) from telegram_login_receipts where id=? and user_id=?", Long.class, id, account)).isEqualTo(1);
        assertThat(apps.getFirst().getBean(IdentityAccounts.class).hasVerifiedContact(account)).isTrue();
        var draft = browser.send("POST", "/api/v1/listings", pgDraft(), true);
        assertThat(draft.statusCode()).isEqualTo(201);
        UUID property = UUID.fromString(json.readTree(draft.body()).get("id").asString());
        assertThat(browser.send("POST", "/api/v1/listings/" + property + "/publish", "{\"version\":0}", true).statusCode()).isEqualTo(409);
        for (String segment : List.of("ROOM", "BATHROOM")) {
            jdbc(0).update("insert into property_images(id,property_id,upload_object_key,object_key,segment,validation_state,upload_expires_at) values (?,?,?,?,?,'VALIDATED',statement_timestamp()+interval '10 minutes')",
                    UUID.randomUUID(), property, "staging/" + UUID.randomUUID(), "final/" + UUID.randomUUID(), segment);
        }
        assertThat(browser.send("POST", "/api/v1/listings/" + property + "/publish", "{\"version\":0}", true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(browser.send("GET", "/api/v1/users/me", null, false).body()).get("phoneVerified").asBoolean()).isTrue();
        assertThat(browser.send("GET", LOGIN + "/" + id, null, false).body()).doesNotContain(number, Long.toString(chat), code);
    }

    /**
     * Exercises interaction authorization, privacy and report conversion using controlled Telegram-created browser sessions.
     */
    @Test void telegramSessionsAuthorizeInteractionsWithoutExposingContactsOrDuplicatingReports() throws Exception {
        var owner = loginBrowser();
        var seeker = loginBrowser();
        var anonymous = new Browser(0);
        UUID ownerId = owner.account();
        UUID seekerId = seeker.account();
        var created = listingJson(owner.send("POST", "/api/v1/listings", pgDraft(), true), 201);
        UUID property = UUID.fromString(created.get("id").asString());
        String listingPath = "/api/v1/listings/" + property;
        String path = "/api/v1/interactions/listings/" + property;
        for (String segment : List.of("ROOM", "BATHROOM")) {
            jdbc(0).update("insert into property_images(id,property_id,upload_object_key,object_key,segment,validation_state,upload_expires_at,declared_content_type,declared_byte_size,content_type,byte_size,width,height) values (?,?,?,?,?,'VALIDATED',statement_timestamp()+interval '10 minutes','image/png',100,'image/png',100,8,8)",
                    UUID.randomUUID(), property, "staging/" + UUID.randomUUID(), "final/" + UUID.randomUUID(), segment);
        }
        listingJson(owner.send("POST", listingPath + "/publish", "{\"version\":0}", true), 200);
        String comment = "Fabricated review preserved after a brokerage allegation.";
        String reviewBody = json.writeValueAsString(Map.of("rating", 4, "issueTag", "GENUINE_OWNER", "commentText", comment));
        for (String action : List.of("reveal-phone", "reviews", "report-brokerage")) {
            String body = action.equals("reviews") ? reviewBody : "{}";
            assertThat(anonymous.send("POST", path + "/" + action, body, true).statusCode()).isEqualTo(401);
            assertThat(seeker.send("POST", path + "/" + action, body, false).statusCode()).isEqualTo(403);
            assertThat(owner.send("POST", path + "/" + action, body, true).statusCode()).isEqualTo(403);
        }
        assertThat(anonymous.send("GET", path + "/state", null, false).statusCode()).isEqualTo(401);
        assertThat(seeker.send("POST", path + "/reviews", reviewBody, true).statusCode()).isEqualTo(403);
        assertThat(seeker.send("POST", path + "/report-brokerage", "{}", true).statusCode()).isEqualTo(403);
        var initial = listingJson(seeker.send("GET", path + "/state", null, false), 200);
        assertThat(initial.get("hasHistoricalReveal").asBoolean()).isFalse();
        assertThat(initial.get("canReview").asBoolean()).isFalse();
        assertThat(initial.get("review").isNull()).isTrue();
        var reveal = listingJson(seeker.send("POST", path + "/reveal-phone", "{}", true), 200);
        String phone = jdbc(0).queryForObject("select phone_number from users where id = ?", String.class, ownerId);
        assertThat(reveal.get("phoneNumber").asString()).isEqualTo(phone);
        assertThat(reveal.get("whatsappUrl").asString()).isEqualTo("https://wa.me/" + phone.substring(1));
        assertThat(reveal.get("remainingReveals").asInt()).isEqualTo(9);
        assertThat(listingJson(seeker.send("POST", path + "/reveal-phone", "{}", true), 200).get("remainingReveals").asInt()).isEqualTo(9);
        assertThat(jdbc(0).queryForObject("select count(*) from contact_reveals where tenant_id = ? and property_id = ?", Long.class, seekerId, property)).isEqualTo(1L);
        var eligible = listingJson(seeker.send("GET", path + "/state", null, false), 200);
        assertThat(eligible.get("revealedToday").asBoolean()).isTrue();
        assertThat(eligible.get("canReview").asBoolean()).isTrue();
        var review = listingJson(seeker.send("POST", path + "/reviews", reviewBody, true), 200);
        assertThat(seeker.send("POST", path + "/reviews", reviewBody, true).statusCode()).isEqualTo(409);
        var report = listingJson(seeker.send("POST", path + "/report-brokerage", "{}", true), 200);
        assertThat(report.get("id").asString()).isEqualTo(review.get("id").asString());
        assertThat(report.get("createdAt").asString()).isEqualTo(review.get("createdAt").asString());
        assertThat(report.get("commentText").asString()).isEqualTo(comment);
        assertThat(report.get("rating").asInt()).isEqualTo(1);
        assertThat(report.get("issueTag").asString()).isEqualTo("ASKED_BROKERAGE");
        assertThat(report.get("isAutoBrokerReport").asBoolean()).isTrue();
        assertThat(report.get("allegationNotice").asString()).contains("allegation");
        assertThat(listingJson(seeker.send("POST", path + "/report-brokerage", "{}", true), 200)).isEqualTo(report);
        assertThat(seeker.send("POST", path + "/reviews", reviewBody, true).statusCode()).isEqualTo(409);
        assertThat(jdbc(0).queryForObject("select count(*) from owner_reviews where reviewer_id = ? and property_id = ?", Long.class, seekerId, property)).isEqualTo(1L);
        var publicReviews = listingJson(anonymous.send("GET", path + "/reviews?page=0&size=1", null, false), 200);
        assertThat(publicReviews.get("totalElements").asLong()).isEqualTo(1);
        assertThat(publicReviews.get("items").get(0)).isEqualTo(report);
        assertThat(publicReviews.toString()).doesNotContain("phoneNumber", "whatsappUrl", "ownerId", "reviewerId", "tenantId");
        var state = listingJson(seeker.send("GET", path + "/state", null, false), 200);
        assertThat(state.get("review")).isEqualTo(report);
        assertThat(state.get("canReview").asBoolean()).isFalse();
        assertThat(state.get("canReportBrokerage").asBoolean()).isFalse();
        assertThat(state.toString()).doesNotContain("phoneNumber", "whatsappUrl", "ownerId", "reviewerId", "tenantId");
        assertThat(listingJson(owner.send("GET", path + "/state", null, false), 200).get("review").isNull()).isTrue();
        var profile = listingJson(owner.send("GET", "/api/v1/users/me", null, false), 200);
        assertThat(profile.get("reviewCount").asLong()).isEqualTo(1);
        assertThat(profile.get("brokerReportCount").asLong()).isEqualTo(1);
        assertThat(profile.get("averageRating").asDouble()).isEqualTo(1);
        assertThat(profile.get("trustBadge").asString()).isEqualTo("CRITICAL_BROKER_AND_LOW_RATING");
        assertThat(listingJson(anonymous.send("GET", listingPath, null, false), 200).get("status").asString()).isEqualTo("ACTIVE");
        listingJson(owner.send("POST", listingPath + "/pause", "{\"version\":1}", true), 200);
        assertThat(anonymous.send("GET", path + "/reviews", null, false).statusCode()).isEqualTo(404);
        assertThat(listingJson(seeker.send("GET", path + "/state", null, false), 200).get("hasHistoricalReveal").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENTIRE_FLAT", "ROOM", "PG_HOSTEL"})
    void telegramSessionsAuthorizeEveryListingApiAndPreserveOwnership(String mode) throws Exception {
        var owner = loginBrowser();
        var attacker = loginBrowser();
        var anonymous = new Browser(0);
        String locality = "API-" + UUID.randomUUID();
        String city = switch (mode) { case "ENTIRE_FLAT" -> "Bengaluru"; case "ROOM" -> "Hyderabad"; default -> "Pune"; };
        String draft = listingDraft(mode, city, locality);
        assertThat(owner.send("POST", "/api/v1/listings", draft, false).statusCode()).isEqualTo(403);
        assertThat(anonymous.send("POST", "/api/v1/listings", draft, true).statusCode()).isEqualTo(401);
        var created = listingJson(owner.send("POST", "/api/v1/listings", draft, true), 201);
        UUID id = UUID.fromString(created.get("id").asString());
        String path = "/api/v1/listings/" + id;
        String basis = switch (mode) { case "ENTIRE_FLAT" -> "PER_PROPERTY"; case "ROOM" -> "PER_ROOM"; default -> "PER_PERSON"; };
        assertThat(created.get("status").asString()).isEqualTo("PAUSED");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("rentBasis").asString()).isEqualTo(basis);
        assertCapacity(created, mode);
        var detail = listingJson(owner.send("GET", path, null, false), 200);
        assertThat(detail.get("privateAddress").asString()).isEqualTo("Fabricated private API address");
        assertThat(detail.has("latitude")).isTrue();
        var mine = listingJson(owner.send("GET", "/api/v1/listings/mine", null, false), 200);
        assertThat(listingIds(mine)).contains(id.toString());
        var others = listingJson(attacker.send("GET", "/api/v1/listings/mine", null, false), 200);
        assertThat(listingIds(others)).doesNotContain(id.toString());
        assertThat(attacker.send("GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(anonymous.send("GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(anonymous.send("GET", "/api/v1/listings/mine", null, false).statusCode()).isEqualTo(401);
        var mutations = listingMutations(path, mode, UUID.randomUUID());
        for (var mutation : mutations) {
            assertThat(owner.send(mutation.method(), mutation.path(), mutation.body(), false).statusCode()).isEqualTo(403);
            assertThat(attacker.send(mutation.method(), mutation.path(), mutation.body(), true).statusCode())
                    .as("Wrong-owner %s %s", mutation.method(), mutation.path()).isEqualTo(403);
            assertThat(anonymous.send(mutation.method(), mutation.path(), mutation.body(), true).statusCode()).isEqualTo(401);
        }
        assertThat(owner.send("POST", path + "/publish", "{\"version\":0}", true).statusCode()).isEqualTo(409);
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", output);
        byte[] bytes = output.toByteArray();
        UUID firstImage = null;
        for (String segment : mode.equals("PG_HOSTEL") ? List.of("ROOM", "BATHROOM") : List.of("ROOM", "HALL", "BATHROOM", "KITCHEN")) {
            String body = json.writeValueAsString(Map.of("segment", segment, "contentType", "image/png", "byteSize", bytes.length));
            var reserved = listingJson(owner.send("POST", path + "/images/presign", body, true), 200);
            assertThat(reserved.get("uploadUrl").asString()).startsWith("https://storage.example.invalid/");
            assertThat(reserved.get("requiredHeaders").get("Content-Type").asString()).isEqualTo("image/png");
            apps.getFirst().getBean(TelegramListingStorageFixture.ControlledListingStorage.class).stage(reserved.get("objectKey").asString(), bytes);
            UUID image = UUID.fromString(reserved.get("imageId").asString());
            if (firstImage == null) firstImage = image;
            String completion = json.writeValueAsString(Map.of("imageId", image));
            var validated = listingJson(owner.send("POST", path + "/images/complete", completion, true), 200);
            assertThat(validated.get("imageId").asString()).isEqualTo(image.toString());
            assertThat(validated.get("width").asInt()).isEqualTo(8);
            assertThat(validated.get("height").asInt()).isEqualTo(8);
            assertThat(validated.get("url").asString()).startsWith("https://media.example.invalid/");
            assertThat(listingJson(owner.send("POST", path + "/images/complete", completion, true), 200).get("imageId").asString()).isEqualTo(image.toString());
            assertThat(attacker.send("POST", path + "/images/complete", completion, true).statusCode()).isEqualTo(403);
        }
        var published = listingJson(owner.send("POST", path + "/publish", "{\"version\":0}", true), 200);
        assertThat(published.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(published.get("version").asLong()).isEqualTo(1);
        assertPublicListing(listingJson(anonymous.send("GET", path, null, false), 200), basis, mode);
        String search = "/api/v1/listings/search?city=" + city + "&locality=" + locality;
        var publicPage = listingJson(anonymous.send("GET", search, null, false), 200);
        assertThat(listingIds(publicPage)).containsExactly(id.toString());
        assertPublicListing(publicPage.get("items").get(0), basis, mode);
        assertPublicListing(listingJson(attacker.send("GET", path, null, false), 200), basis, mode);
        var full = listingJson(owner.send("PATCH", path + "/inventory", listingInventory(mode, 1, false), true), 200);
        assertThat(full.get("status").asString()).isEqualTo("FULL_OCCUPIED");
        assertThat(full.get("version").asLong()).isEqualTo(2);
        assertCapacity(full, mode);
        assertThat(owner.send("PATCH", path + "/inventory", listingInventory(mode, 1, true), true).statusCode()).isEqualTo(409);
        assertThat(listingJson(anonymous.send("GET", search, null, false), 200).get("items").size()).isZero();
        assertThat(listingIds(listingJson(anonymous.send("GET", search + "&status=FULL_OCCUPIED", null, false), 200))).containsExactly(id.toString());
        var paused = listingJson(owner.send("POST", path + "/pause", "{\"version\":2}", true), 200);
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("version").asLong()).isEqualTo(3);
        assertThat(anonymous.send("GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(listingJson(anonymous.send("GET", search + "&status=FULL_OCCUPIED", null, false), 200).get("items").size()).isZero();
        var editedDraft = listingJson(owner.send("PATCH", path + "/inventory", listingInventory(mode, 3, true), true), 200);
        assertThat(editedDraft.get("status").asString()).isEqualTo("PAUSED");
        assertThat(editedDraft.get("version").asLong()).isEqualTo(4);
        var republished = listingJson(owner.send("POST", path + "/publish", "{\"version\":4}", true), 200);
        assertThat(republished.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(republished.get("version").asLong()).isEqualTo(5);
        assertThat(owner.send("POST", "/api/v1/auth/logout", null, true).statusCode()).isEqualTo(204);
        owner.refreshCsrf();
        assertThat(owner.send("GET", "/api/v1/listings/mine", null, false).statusCode()).isEqualTo(401);
        assertThat(owner.send("GET", "/api/v1/users/me", null, false).statusCode()).isEqualTo(401);
        assertThat(owner.send("POST", "/api/v1/listings", draft, true).statusCode()).isEqualTo(401);
        for (var mutation : listingMutations(path, mode, firstImage))
            assertThat(owner.send(mutation.method(), mutation.path(), mutation.body(), true).statusCode()).isEqualTo(401);
        assertPublicListing(listingJson(owner.send("GET", path, null, false), 200), basis, mode);
    }

    private JsonNode listingJson(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("Listing HTTP response: %s", response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return json.readTree(response.body());
    }
    private List<String> listingIds(JsonNode page) {
        var ids = new ArrayList<String>();
        for (var item : page.get("items")) ids.add(item.get("id").asString());
        return ids;
    }
    private void assertPublicListing(JsonNode listing, String basis, String mode) {
        for (String field : List.of("phoneNumber", "privateAddress", "latitude", "longitude", "version")) assertThat(listing.has(field)).isFalse();
        assertThat(listing.get("owner").has("phoneNumber")).isFalse();
        assertThat(listing.get("rentBasis").asString()).isEqualTo(basis);
        assertThat(listing.get("currency").asString()).isEqualTo("INR");
        assertThat(listing.get("rentPeriod").asString()).isEqualTo("MONTH");
        assertCapacity(listing, mode);
    }
    private void assertCapacity(JsonNode listing, String mode) {
        if (mode.equals("ENTIRE_FLAT")) {
            assertThat(listing.get("maxOccupants").asInt()).isEqualTo(3);
            assertThat(listing.get("maxSharingPerRoom").isNull()).isTrue();
            assertThat(listing.get("totalRoomsAvailable").isNull()).isTrue();
        } else {
            assertThat(listing.get("maxSharingPerRoom").asInt()).isEqualTo(2);
            assertThat(listing.get("maxOccupants").isNull()).isTrue();
        }
    }
    private String listingDraft(String mode, String city, String locality) {
        var body = new LinkedHashMap<String, Object>(Map.of("title", "Fabricated API listing", "description", "Fabricated Telegram session API verification",
                "city", city, "locality", locality, "privateAddress", "Fabricated private API address", "latitude", 18.52, "longitude", 73.85,
                "monthlyRent", 5000, "securityDeposit", 0));
        body.putAll(Map.of("category", mode.equals("PG_HOSTEL") ? "PG_HOSTEL" : "FLAT", "hasWifi", true, "hasGym", false, "hasSwimmingPool", false));
        if (!mode.equals("PG_HOSTEL")) { body.put("flatRentalMode", mode); body.put("bedrooms", 2); }
        if (mode.equals("ENTIRE_FLAT")) body.putAll(Map.of("wholeFlatAvailable", true, "maxOccupants", 3));
        else body.putAll(Map.of("maleRoomsAvailable", 1, "femaleRoomsAvailable", 0, "sharedRoomsAvailable", 0, "maxSharingPerRoom", 2));
        return json.writeValueAsString(body);
    }
    private String listingInventory(String mode, long version, boolean available) {
        return json.writeValueAsString(mode.equals("ENTIRE_FLAT") ? Map.of("version", version, "wholeFlatAvailable", available, "maxOccupants", 3)
                : Map.of("version", version, "maleRoomsAvailable", available ? 1 : 0, "femaleRoomsAvailable", 0, "sharedRoomsAvailable", 0, "maxSharingPerRoom", 2));
    }
    private List<ListingRequest> listingMutations(String path, String mode, UUID image) {
        return List.of(new ListingRequest("POST", path + "/publish", "{\"version\":0}"),
                new ListingRequest("POST", path + "/pause", "{\"version\":0}"),
                new ListingRequest("PATCH", path + "/inventory", listingInventory(mode, 0, true)),
                new ListingRequest("POST", path + "/images/presign", "{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":100}"),
                new ListingRequest("POST", path + "/images/complete", json.writeValueAsString(Map.of("imageId", image))));
    }
    private record ListingRequest(String method, String path, String body) { }

    @ParameterizedTest
    @ValueSource(strings = {"different_user", "missing_user", "wrong_phone", "group", "forwarded", "bot", "stale"})
    void rejectsUntrustedOrMismatchedContactEvents(String variant) {
        var flow = begin(phone());
        var event = contact(flow.chat(), flow.phone());
        var message = (tools.jackson.databind.node.ObjectNode) event.get("message");
        var shared = (tools.jackson.databind.node.ObjectNode) message.get("contact");
        switch (variant) {
            case "different_user" -> shared.put("user_id", flow.chat() + 1);
            case "missing_user" -> shared.remove("user_id");
            case "wrong_phone" -> shared.put("phone_number", phone());
            case "group" -> ((tools.jackson.databind.node.ObjectNode) message.get("chat")).put("type", "group");
            case "forwarded" -> message.putObject("forward_origin").put("type", "user");
            case "bot" -> ((tools.jackson.databind.node.ObjectNode) message.get("from")).put("is_bot", true);
            case "stale" -> message.put("date", Instant.now().getEpochSecond() - 400);
        }
        receiver().accept(event);
        assertThat(bot().codes).doesNotContainKey(flow.chat());
        assertThat(challenges().find(flow.id()).state()).isEqualTo("WAITING_CONTACT");
        assertThat(identityCount(flow.chat())).isZero();
        assertThat(phoneOwners(flow.phone())).isZero();
    }

    @Test void anotherAnonymousSessionCannotReadOrConsumeTheCode() {
        var flow = ready();
        String code = bot().codes.get(flow.chat());
        String other = contacts().session("other-fabricated-session");
        assertThatThrownBy(() -> contacts().complete(flow.id(), other, code))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.NOT_FOUND));
        assertThatThrownBy(() -> contacts().status(flow.id(), other)).isInstanceOf(RequestFailure.class);
        UUID account = contacts().complete(flow.id(), flow.session(), code);
        assertThat(jdbc(0).queryForObject("select phone_number from users where id=?", String.class, account)).isEqualTo(flow.phone());
        assertThatThrownBy(() -> contacts().complete(flow.id(), other, code)).isInstanceOf(RequestFailure.class);
    }

    @Test void fiveBadAttemptsLockTheChallengeAndExpiryFailsClosed() {
        var flow = ready();
        String correct = bot().codes.get(flow.chat());
        String bad = correct.equals("000000") ? "999999" : "000000";
        for (int index = 0; index < 5; index++) {
            assertThatThrownBy(() -> contacts().complete(flow.id(), flow.session(), bad)).isInstanceOf(RequestFailure.class);
        }
        assertThat(challenges().find(flow.id()).state()).isEqualTo("LOCKED");
        assertThatThrownBy(() -> contacts().complete(flow.id(), flow.session(), correct)).isInstanceOf(RequestFailure.class);
        var expired = ready();
        challenges().redis.delete(challenges().key(expired.id()));
        assertThatThrownBy(() -> contacts().complete(expired.id(), expired.session(), bot().codes.get(expired.chat()))).isInstanceOf(RequestFailure.class);
        assertThat(identityCount(expired.chat())).isZero();
    }

    @Test void duplicateUpdatesAndConcurrentCompletionCreateOneReceipt() throws Exception {
        var flow = begin(phone());
        var event = contact(flow.chat(), flow.phone());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var events = new ArrayList<Future<?>>();
            for (int index = 0; index < 2; index++) {
                events.add(executor.submit(() -> {
                    start.await();
                    try { receiver().accept(event); }
                    catch (RequestFailure failure) { assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.UNAVAILABLE); }
                    return null;
                }));
            }
            start.countDown();
            for (var future : events) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(bot().sent.get(flow.chat()).get()).isEqualTo(1);
        UUID account;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<UUID>>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> { gate.await(); return complete(flow); }));
            }
            gate.countDown();
            account = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(account);
        }
        challenges().redis.delete(challenges().key(flow.id()));
        assertThat(complete(flow)).isEqualTo(account);
        assertThat(jdbc(0).queryForObject("select count(*) from telegram_login_receipts where id=?", Long.class, flow.id())).isEqualTo(1);
        assertThat(identityCount(flow.chat())).isEqualTo(1);
    }

    @Test void phoneConflictRollsBackSignupAndNeverLinksByPhoneAlone() {
        String number = phone();
        UUID original = owner(0);
        jdbc(0).update("update users set phone_number=?,phone_verified_at=statement_timestamp() where id=?", number, original);
        var flow = begin(number);
        receiver().accept(contact(flow.chat(), number));
        assertThatThrownBy(() -> complete(flow)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(phoneOwners(number)).isEqualTo(1);
        assertThat(identityCount(flow.chat())).isZero();
        assertThat(jdbc(0).queryForObject("select count(*) from telegram_login_receipts where id=?", Long.class, flow.id())).isZero();
        assertThat(challenges().find(flow.id()).state()).isEqualTo("COMPLETING");
        jdbc(0).update("update users set phone_number=null,phone_verified_at=null where id=?", original);
        UUID account = complete(flow);
        assertThat(account).isNotEqualTo(original);
        assertThat(identityCount(flow.chat())).isEqualTo(1);
    }

    @Test void providerFailureRetriesTheSameCodeWithoutCreatingAnIdentity() {
        var flow = begin(phone());
        var event = contact(flow.chat(), flow.phone());
        bot().fail.set(true);
        assertThatThrownBy(() -> receiver().accept(event)).isInstanceOf(RequestFailure.class);
        String attempted = bot().codes.get(flow.chat());
        assertThat(challenges().find(flow.id()).state()).isEqualTo("CODE_READY");
        clearBudget(flow.chat());
        receiver().accept(event);
        assertThat(bot().codes.get(flow.chat())).isEqualTo(attempted);
        assertThat(challenges().find(flow.id()).state()).isEqualTo("CODE_SENT");
        assertThat(identityCount(flow.chat())).isZero();
        assertThat(phoneOwners(flow.phone())).isZero();
    }

    @Test void restartInvalidationAndRollingLimitsStayInRedis() {
        String number = phone();
        String session = contacts().session("rate-session");
        UUID initiator = contacts().initiator(session);
        var first = contacts().create(session, number);
        assertThatThrownBy(() -> contacts().create(session, number))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.RATE_LIMITED));
        for (int index = 1; index < 5; index++) {
            challenges().redis.delete(challenges().prefix + "cooldown:" + initiator);
            contacts().create(session, number);
        }
        assertThat(challenges().find((UUID) first.get("challengeId")).state()).isEqualTo("CANCELLED");
        challenges().redis.delete(challenges().prefix + "cooldown:" + initiator);
        assertThatThrownBy(() -> contacts().create(session, number)).isInstanceOf(RequestFailure.class);
        assertThat(contacts().create(contacts().session("different-rate-session"), number)).containsKey("challengeId");
    }

    @Test void onlyMatchedContactsConsumeTheAtomicPhoneBudget() throws Exception {
        String number = phone();
        var flows = new ArrayList<Flow>();
        for (int index = 0; index < 6; index++) flows.add(begin(number));
        String rate = challenges().prefix + "rate:phone:" + apps.getFirst().getBean(TelegramCrypto.class).hmac("phone", number);
        receiver().accept(contact(flows.getFirst().chat(), phone()));
        assertThat(challenges().redis.opsForZSet().zCard(rate)).isZero();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = flows.stream().map(flow -> executor.submit(() -> challenges().prepare(challenges().find(flow.id()), flow.chat(), "004217"))).toList();
            int accepted = 0;
            for (var task : tasks) if (task.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(challenges().redis.opsForZSet().zCard(rate)).isEqualTo(5);
        var prepared = flows.stream().filter(flow -> "CODE_READY".equals(challenges().find(flow.id()).state())).findFirst().orElseThrow();
        assertThat(challenges().prepare(challenges().find(prepared.id()), prepared.chat(), "004217")).isTrue();
        assertThat(challenges().redis.opsForZSet().zCard(rate)).isEqualTo(5);
        var blocked = flows.stream().filter(flow -> "WAITING_CONTACT".equals(challenges().find(flow.id()).state())).findFirst().orElseThrow();
        receiver().accept(contact(blocked.chat(), number));
        assertThat(bot().codes).doesNotContainKey(blocked.chat());
    }

    @Test void webhookSecretAndDeploymentIsolationRejectReplay() throws Exception {
        var flow = begin(phone());
        var event = contact(flow.chat(), flow.phone());
        assertThat(webhook(0, event, false).statusCode()).isEqualTo(403);
        assertThat(webhook(1, event, true).statusCode()).isEqualTo(204);
        assertThat(apps.get(1).getBean(ControlledTelegram.class).codes).doesNotContainKey(flow.chat());
        assertThatThrownBy(() -> apps.get(1).getBean(TelegramContacts.class)
                .complete(flow.id(), flow.session(), "000000")).isInstanceOf(RequestFailure.class);
        assertThat(identityCount(flow.chat())).isZero();
    }

    @Test void browserLoginRotatesSessionAndCsrfThenLogoutInvalidatesIt() throws Exception {
        var browser = new Browser(0);
        String initialCookie = browser.cookie;
        String initialCsrf = browser.csrf;
        var flow = ready(browser);
        assertThat(browser.send("POST", LOGIN + "/" + flow.id() + "/complete",
                json.writeValueAsString(Map.of("code", bot().codes.get(flow.chat()))), true).statusCode()).isEqualTo(200);
        assertThat(browser.cookie).isNotEqualTo(initialCookie);
        assertThat(raw("GET", "/api/v1/users/me", initialCookie, null, null).statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", "/api/v1/listings", pgDraft(), true).statusCode()).isEqualTo(403);
        browser.refreshCsrf();
        assertThat(browser.csrf).isNotEqualTo(initialCsrf);
        assertThat(browser.account()).isNotNull();
        assertThat(browser.send("POST", "/api/v1/auth/logout", null, false).statusCode()).isEqualTo(403);
        String authenticatedCookie = browser.cookie;
        assertThat(browser.send("POST", "/api/v1/auth/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(raw("GET", "/api/v1/users/me", authenticatedCookie, null, null).statusCode()).isEqualTo(401);
    }

    @Test void authenticatedBrowserCannotUseRemovedGoogleOrContactVerificationRoutes() throws Exception {
        var browser = loginBrowser();
        var anonymous = new Browser(0);
        for (var client : List.of(anonymous, browser)) {
            int denied = client == anonymous ? 401 : 403;
            for (String path : List.of("/oauth2/authorization/google", "/login/oauth2/code/google",
                    "/oauth2/authorization/other", "/login/oauth2/code/other", "/login", "/logout")) {
                for (String method : List.of("GET", "POST")) {
                    var response = client.send(method, path, method.equals("POST") ? "{}" : null, method.equals("POST"));
                    assertThat(response.statusCode()).isEqualTo(denied);
                    assertThat(response.headers().firstValue("Location")).isEmpty();
                    assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
                }
            }
            assertThat(client.send("GET", "/api/v1/users/me/contact-verifications/" + UUID.randomUUID(), null, false).statusCode()).isEqualTo(denied);
            assertThat(client.send("POST", "/api/v1/users/me/contact-verifications/telegram", "{}", true).statusCode()).isEqualTo(denied);
        }
        assertThat(browser.send("POST", LOGIN, json.writeValueAsString(Map.of("phoneNumber", phone())), true).statusCode()).isEqualTo(409);
    }

    @Test void expiredAndOtherRealmSessionsCannotAuthorizeRequests() throws Exception {
        UUID account = owner(0);
        for (var principal : List.of(
                new SessionPrincipal(account, "production:primary", Instant.now().minusSeconds(25 * 3600)),
                new SessionPrincipal(account, "development:primary", Instant.now()),
                new SessionPrincipal(account, "production:primary", Instant.now().plusSeconds(60)))) {
            String cookie = sessionCookie(apps.getFirst().getBean(RedisSessionRepository.class), principal, 0);
            assertThat(raw("GET", "/api/v1/users/me", cookie, null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test void legacySessionNamespaceCannotReplayAgainstTelegramAuthentication() throws Exception {
        String namespace = apps.getFirst().getEnvironment().getRequiredProperty("spring.session.redis.namespace");
        assertThat(namespace).isEqualTo("opennest:production:primary:telegram-auth:v1:sessions");
        String cookie = sessionCookie(apps.getFirst().getBean(RedisSessionRepository.class),
                new SessionPrincipal(owner(0), "production:primary", Instant.now()), 0);
        String sessionId = new String(Base64.getDecoder().decode(cookie.substring(cookie.indexOf('=') + 1)), StandardCharsets.UTF_8);
        var keys = challenges().redis.keys(namespace + "*:" + sessionId);
        assertThat(keys).hasSize(1);
        String currentKey = keys.iterator().next();
        String legacyKey = currentKey.replace(":telegram-auth:v1", "");
        challenges().redis.rename(currentKey, legacyKey);
        assertThat(raw("GET", "/api/v1/users/me", cookie, null, null).statusCode()).isEqualTo(401);
        assertThat(challenges().redis.hasKey(legacyKey)).isTrue();
    }

    @Test void exactOriginCorsAppliesToAnonymousLoginAndAuthenticatedRequests() throws Exception {
        var allowed = raw("OPTIONS", LOGIN, null, "https://opennest.example.invalid", "POST");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        var blocked = raw("OPTIONS", LOGIN, null, "https://blocked.example.invalid", "POST");
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(blocked.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        var browser = loginBrowser();
        var profile = raw("GET", "/api/v1/users/me", browser.cookie, "https://opennest.example.invalid", null);
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
        assertThat(raw("GET", "/api/v1/users/me", browser.cookie, "https://blocked.example.invalid", null).statusCode()).isEqualTo(403);
    }

    @Test void returningSenderUsesSameAccountAndCannotReplaceItsContact() {
        var original = ready();
        UUID account = complete(original);
        var returning = ready(original.phone(), original.chat());
        assertThat(complete(returning)).isEqualTo(account);
        assertThat(identityCount(original.chat())).isEqualTo(1);
        var changed = ready(phone(), original.chat());
        assertThatThrownBy(() -> complete(changed))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONFLICT));
        assertThat(jdbc(0).queryForObject("select phone_number from users where id=?", String.class, account)).isEqualTo(original.phone());
    }

    @Test void concurrentChallengesForTheSameSenderCreateOneAccount() throws Exception {
        String number = phone();
        long chat = chats.incrementAndGet();
        var first = ready(number, chat);
        challenges().redis.delete(challenges().prefix + "chat:" + chat);
        var second = ready(number, chat);
        UUID firstAccount;
        UUID secondAccount;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var one = executor.submit(() -> { gate.await(); return contacts().complete(first.id(), first.session(), code(first)); });
            var two = executor.submit(() -> { gate.await(); return contacts().complete(second.id(), second.session(), code(second)); });
            gate.countDown();
            firstAccount = one.get(10, TimeUnit.SECONDS);
            secondAccount = two.get(10, TimeUnit.SECONDS);
        }
        assertThat(firstAccount).isEqualTo(secondAccount);
        assertThat(identityCount(chat)).isEqualTo(1);
        assertThat(phoneOwners(number)).isEqualTo(1);
        assertThat(jdbc(0).queryForObject("select count(*) from telegram_login_receipts where user_id=?", Long.class, firstAccount)).isEqualTo(2);
    }

    @Test void uniqueLegacyTelegramReceiptRecoversItsExistingAccount() {
        long chat = chats.incrementAndGet();
        String number = phone();
        UUID original = legacyReceipt(chat, number);
        jdbc(0).update("insert into auth_identities(id,user_id,issuer,subject) values (?,?,?,?)",
                UUID.randomUUID(), original, "https://accounts.google.com", "fabricated-legacy-subject-" + original);
        var flow = ready(number, chat);
        assertThat(complete(flow)).isEqualTo(original);
        assertThat(jdbc(0).queryForObject("select count(*) from auth_identities where user_id=?", Long.class, original)).isEqualTo(2);
        assertThat(identityCount(chat)).isEqualTo(1);
        assertThat(jdbc(0).queryForObject("select count(*) from contact_verifications where user_id=?", Long.class, original)).isEqualTo(1);
    }

    @Test void ambiguousLegacyReceiptsNeverChooseAnAccount() {
        long chat = chats.incrementAndGet();
        String firstPhone = phone();
        UUID first = legacyReceipt(chat, firstPhone);
        UUID second = legacyReceipt(chat, phone());
        var flow = ready(firstPhone, chat);
        assertThatThrownBy(() -> complete(flow))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONFLICT));
        assertThat(identityCount(chat)).isZero();
        assertThat(jdbc(0).queryForObject("select phone_number from users where id=?", String.class, first)).isEqualTo(firstPhone);
        assertThat(jdbc(0).queryForObject("select count(*) from contact_verifications where user_id in (?,?)", Long.class, first, second)).isEqualTo(2);
    }

    @Test void statusRateAndStrictLoginBodiesRemainBounded() throws Exception {
        var browser = new Browser(0);
        assertThat(browser.send("POST", LOGIN, "{\"phoneNumber\":\"+910000000000\",\"phoneNumber\":\"+910000000001\"}", true).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", LOGIN, "{\"phoneNumber\":\"+910000000000\",\"extra\":true}", true).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", LOGIN, "{\"phoneNumber\":\"" + "0".repeat(9000) + "\"}", true).statusCode()).isEqualTo(413);
        var flow = begin(phone());
        assertThat(contacts().status(flow.id(), flow.session())).containsEntry("status", "WAITING_CONTACT");
        assertThatThrownBy(() -> contacts().status(flow.id(), flow.session()))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.RATE_LIMITED));
    }

    @Test void localPollingSerializesConsumersAndPersistsTheAcknowledgedOffset() throws Exception {
        var application = apps.get(1);
        var contacts = application.getBean(TelegramContacts.class);
        var store = application.getBean(TelegramChallenges.class);
        var provider = application.getBean(ControlledTelegram.class);
        var response = contacts.create(contacts.session("local-polling-session"), phone());
        long chat = chats.incrementAndGet();
        var event = start(chat, response.get("botUrl").toString().split("start=")[1]);
        provider.batch.add(event); provider.entered = new CountDownLatch(1); provider.release = new CountDownLatch(1);
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("opennest.deployment.environment", "DEVELOPMENT")
                .withProperty("opennest.identity.telegram-enabled", "true")
                .withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true").withProperty("TELEGRAM_BOT_USERNAME", "OpenNestTestBot")
                .withProperty("TELEGRAM_BOT_TOKEN", "1234567:AAAAAAAAAAAAAAAAAAAAAAAAAA")
                .withProperty("TELEGRAM_OTP_HMAC_KEY", "fabricated-hmac-secret-at-least-32-characters-1");
        var settings = new TelegramSettings(environment);
        var first = new TelegramPolling(settings, store, application.getBean(TelegramUpdates.class), provider);
        var second = new TelegramPolling(settings, store, application.getBean(TelegramUpdates.class), provider);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(first::poll);
            assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue();
            second.poll();
            assertThat(provider.pollCalls.get()).isEqualTo(1);
            provider.release.countDown();
            running.get(5, TimeUnit.SECONDS);
        } finally { provider.release.countDown(); }
        assertThat(store.redis.opsForValue().get(store.prefix + "offset")).isEqualTo(Long.toString(event.get("update_id").asLong() + 1));
        assertThat(store.redis.opsForValue().get(store.prefix + "poller")).isNull();
        assertThat(store.find((UUID) response.get("challengeId")).state()).isEqualTo("WAITING_CONTACT");
    }

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void databaseOutageRejectsProfilesAndCannotAuthenticatePendingLogin() throws Exception {
        var authenticated = loginBrowser();
        var anonymous = new Browser(0);
        var pending = ready(anonymous);
        String code = bot().codes.get(pending.chat());
        databases.getFirst().stop();
        apps.getFirst().getBean(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        assertUnavailable(authenticated.send("GET", "/api/v1/users/me", null, false));
        var failure = anonymous.send("POST", LOGIN + "/" + pending.id() + "/complete",
                json.writeValueAsString(Map.of("code", code)), true);
        assertUnavailable(failure);
        assertThat(failure.body()).doesNotContain(pending.phone(), code, pending.id().toString());
        assertThat(anonymous.send("GET", "/api/v1/users/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void redisOutageRejectsExistingSessionsAndAnonymousCsrfCreation() throws Exception {
        var browser = loginBrowser(1);
        assertThat(browser.account()).isNotNull();
        stores.get(1).stop();
        assertUnavailable(browser.send("GET", "/api/v1/users/me", null, false));
        var fresh = http.send(HttpRequest.newBuilder(URI.create(origin(1) + "/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertUnavailable(fresh);
    }

    private void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        var problem = json.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(503);
        assertThat(problem.get("detail").asString()).isEqualTo("The service is temporarily unavailable.");
        assertThat(response.body()).doesNotContain("fabricated-", "password", "JDBC", "Redis", "Exception", "telegram_login_receipts");
    }

    private Flow begin(String phone) { return begin(phone, chats.incrementAndGet()); }
    private Flow begin(String phone, long chat) {
        String session = contacts().session("fabricated-session-" + UUID.randomUUID());
        var response = contacts().create(session, phone);
        UUID id = (UUID) response.get("challengeId");
        clearBudget(chat);
        receiver().accept(start(chat, response.get("botUrl").toString().split("start=")[1]));
        clearBudget(chat);
        return new Flow(id, contacts().initiator(session), session, phone, chat);
    }
    private Flow ready() { return ready(phone(), chats.incrementAndGet()); }
    private Flow ready(String number, long chat) {
        var flow = begin(number, chat);
        receiver().accept(contact(flow.chat(), flow.phone()));
        return flow;
    }
    private Flow ready(Browser browser) throws Exception {
        String number = phone();
        var created = browser.send("POST", LOGIN, json.writeValueAsString(Map.of("phoneNumber", number)), true);
        assertThat(created.statusCode()).isEqualTo(200);
        var response = json.readTree(created.body());
        UUID id = UUID.fromString(response.get("challengeId").asString());
        long chat = chats.incrementAndGet();
        clearBudget(browser.target, chat);
        var receiver = apps.get(browser.target).getBean(TelegramUpdates.class);
        receiver.accept(start(chat, response.get("botUrl").asString().split("start=")[1]));
        clearBudget(browser.target, chat);
        receiver.accept(contact(chat, number));
        var challenge = apps.get(browser.target).getBean(TelegramChallenges.class).find(id);
        return new Flow(id, challenge.user(), challenge.session(), number, chat);
    }
    private UUID complete(Flow flow) { return contacts().complete(flow.id(), flow.session(), code(flow)); }
    private String code(Flow flow) {
        var challenge = challenges().find(flow.id());
        return challenge == null ? bot().codes.get(flow.chat()) : apps.getFirst().getBean(TelegramCrypto.class).code(challenge.salt());
    }
    private Browser loginBrowser() throws Exception { return loginBrowser(0); }
    private Browser loginBrowser(int target) throws Exception {
        var browser = new Browser(target);
        var flow = ready(browser);
        String code = apps.get(target).getBean(ControlledTelegram.class).codes.get(flow.chat());
        assertThat(browser.send("POST", LOGIN + "/" + flow.id() + "/complete", json.writeValueAsString(Map.of("code", code)), true).statusCode()).isEqualTo(200);
        browser.refreshCsrf();
        return browser;
    }
    private record Flow(UUID id, UUID owner, String session, String phone, long chat) {
        @Override public String toString() { return "Flow[private]"; }
    }
    private void clearBudget(long chat) { clearBudget(0, chat); }
    private void clearBudget(int target, long chat) {
        var store = apps.get(target).getBean(TelegramChallenges.class);
        store.redis.delete(store.prefix + "send:chat:" + chat);
        var seconds = store.redis.keys(store.prefix + "send:second:*");
        if (seconds != null && !seconds.isEmpty()) store.redis.delete(seconds);
    }
    private JsonNode start(long chat, String token) { return event(chat, Map.of("text", "/start " + token)); }
    private JsonNode contact(long chat, String phone) { return event(chat, Map.of("contact", Map.of("user_id", chat, "phone_number", phone, "first_name", "Fabricated"))); }
    private JsonNode event(long chat, Map<String, Object> content) {
        var message = new LinkedHashMap<String, Object>(content);
        message.putAll(Map.of("date", Instant.now().getEpochSecond(), "chat", Map.of("id", chat, "type", "private"), "from", Map.of("id", chat, "is_bot", false)));
        return json.readTree(json.writeValueAsString(Map.of("update_id", updates.incrementAndGet(), "message", message)));
    }
    private UUID owner(int target) {
        UUID id = UUID.randomUUID();
        jdbc(target).update("insert into users(id,full_name) values (?,?)", id, "Fabricated Telegram owner");
        return id;
    }
    private UUID legacyReceipt(long chat, String number) {
        UUID account = owner(0);
        jdbc(0).update("update users set phone_number=?,phone_verified_at=statement_timestamp() where id=?", number, account);
        jdbc(0).update("insert into contact_verifications(id,user_id,phone_number,telegram_user_id,session_digest) values (?,?,?,?,?)",
                UUID.randomUUID(), account, number, chat, contacts().session("fabricated-legacy-session-" + account));
        return account;
    }
    private long phoneOwners(String number) { return jdbc(0).queryForObject("select count(*) from users where phone_number=?", Long.class, number); }
    private long identityCount(long chat) { return jdbc(0).queryForObject("select count(*) from auth_identities where issuer=? and subject=?", Long.class, ISSUER, Long.toString(chat)); }
    private String phone() { return "+910" + String.format(java.util.Locale.ROOT, "%09d", phones.incrementAndGet()); }
    private JdbcTemplate jdbc(int index) { return apps.get(index).getBean(JdbcTemplate.class); }
    private TelegramContacts contacts() { return apps.getFirst().getBean(TelegramContacts.class); }
    private TelegramChallenges challenges() { return apps.getFirst().getBean(TelegramChallenges.class); }
    private TelegramUpdates receiver() { return apps.getFirst().getBean(TelegramUpdates.class); }
    private ControlledTelegram bot() { return apps.getFirst().getBean(ControlledTelegram.class); }
    private HttpResponse<String> webhook(int target, JsonNode body, boolean valid) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin(target) + "/api/v1/webhooks/telegram"))
                .header("Content-Type", "application/json").header("X-Telegram-Bot-Api-Secret-Token", valid ? apps.get(target).getBean(TelegramSettings.class).webhookSecret() : "incorrect")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> raw(String method, String path, String cookie, String origin, String preflightMethod) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin(0) + path));
        if (cookie != null && !cookie.isEmpty()) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (preflightMethod != null) request.header("Access-Control-Request-Method", preflightMethod).header("Access-Control-Request-Headers", "content-type,x-csrf-token");
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String origin(int target) { return "http://127.0.0.1:" + apps.get(target).getEnvironment().getProperty("local.server.port"); }
    private String pgDraft() { return """
            {"title":"Fabricated PG","description":"Fabricated Telegram verification listing","category":"PG_HOSTEL","city":"Pune","locality":"Test",
             "privateAddress":"Private fabricated address","latitude":18.52,"longitude":73.85,"monthlyRent":5000,"securityDeposit":0,
             "hasWifi":false,"hasGym":false,"hasSwimmingPool":false,"maleRoomsAvailable":1,"femaleRoomsAvailable":0,"sharedRoomsAvailable":0,"maxSharingPerRoom":2}
            """; }
    private <S extends org.springframework.session.Session> String sessionCookie(org.springframework.session.SessionRepository<S> repository, SessionPrincipal principal, int target) {
        S session = repository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())));
        repository.save(session);
        return (target == 0 ? "__Host-OPENNEST_SESSION=" : "OPENNEST_SESSION=") + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
    }
    private final class Browser {
        final int target;
        String cookie;
        String csrf;
        Browser(int target) throws Exception { this.target = target; refreshCsrf(); }
        void refreshCsrf() throws Exception {
            var response = send("GET", "/api/v1/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf = json.readTree(response.body()).get("token").asString();
        }
        UUID account() throws Exception {
            var response = send("GET", "/api/v1/users/me", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            return UUID.fromString(json.readTree(response.body()).get("id").asString());
        }
        HttpResponse<String> send(String method, String path, String body, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin(target) + path));
            if (cookie != null && !cookie.isEmpty()) request.header("Cookie", cookie);
            if (token) request.header("X-CSRF-TOKEN", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            String name = target == 0 ? "__Host-OPENNEST_SESSION=" : "OPENNEST_SESSION=";
            for (String header : response.headers().allValues("Set-Cookie")) {
                if (header.startsWith(name)) cookie = header.split(";", 2)[0];
            }
            return response;
        }
    }
    private Map<String, String> settings(int target) {
        return Map.ofEntries(Map.entry("server.port", "0"), Map.entry("server.address", "127.0.0.1"), Map.entry("OPENNEST_ENVIRONMENT", target == 0 ? "PRODUCTION" : "DEVELOPMENT"),
                Map.entry("OPENNEST_BACKEND_ORIGIN", "https://api.opennest.example.invalid"), Map.entry("OPENNEST_FRONTEND_ORIGIN", "https://opennest.example.invalid"),
                Map.entry("DB_HOST", databases.get(target).getHost()), Map.entry("DB_PORT", databases.get(target).getMappedPort(5432).toString()),
                Map.entry("DB_NAME", databases.get(target).getDatabaseName()), Map.entry("DB_USER", databases.get(target).getUsername()), Map.entry("DB_PASSWORD", databases.get(target).getPassword()),
                Map.entry("REDIS_HOST", stores.get(target).getHost()), Map.entry("REDIS_PORT", stores.get(target).getMappedPort(6379).toString()), Map.entry("REDIS_PASSWORD", "fabricated-telegram-cache-" + target),
                Map.entry("OPENNEST_TELEGRAM_AUTH_ENABLED", "true"), Map.entry("TELEGRAM_BOT_USERNAME", "OpenNestTestBot"), Map.entry("TELEGRAM_BOT_TOKEN", "1234567:AAAAAAAAAAAAAAAAAAAAAAAAAA"),
                Map.entry("TELEGRAM_WEBHOOK_SECRET", "fabricated-webhook-secret-at-least-32-characters-" + target), Map.entry("TELEGRAM_OTP_HMAC_KEY", "fabricated-hmac-secret-at-least-32-characters-" + target));
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class BotConfiguration {
        @Bean @Primary ControlledTelegram controlledTelegram() { return new ControlledTelegram(); }
        @Bean LettuceClientConfigurationBuilderCustomizer boundedRedisCommands() {
            return builder -> builder.commandTimeout(Duration.ofMillis(400)).shutdownTimeout(Duration.ZERO);
        }
    }
    static class ControlledTelegram implements TelegramBot {
        final Map<Long, String> codes = new ConcurrentHashMap<>();
        final Map<Long, AtomicInteger> sent = new ConcurrentHashMap<>();
        final AtomicBoolean fail = new AtomicBoolean();
        final Queue<JsonNode> batch = new ConcurrentLinkedQueue<>();
        final AtomicInteger pollCalls = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
        public void contact(long chat) { }
        public void code(long chat, String code) {
            codes.put(chat, code);
            if (fail.getAndSet(false)) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
            sent.computeIfAbsent(chat, ignored -> new AtomicInteger()).incrementAndGet();
        }
        public void checkPolling() { }
        public JsonNode updates(long offset) {
            pollCalls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled polling timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Controlled polling interrupted"); }
            var result = new JsonMapper().createArrayNode();
            var update = batch.poll();
            if (update != null) result.add(update);
            return result;
        }
    }
}

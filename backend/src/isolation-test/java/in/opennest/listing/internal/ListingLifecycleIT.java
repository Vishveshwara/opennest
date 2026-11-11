package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import in.opennest.OpenNestApplication;
import in.opennest.common.RequestFailure;
import in.opennest.identity.internal.security.SessionPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ListingLifecycleIT {
    private final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private final List<PostgreSQLContainer> databases = new ArrayList<>();
    private final List<GenericContainer<?>> redis = new ArrayList<>();
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll void start() {
        for (int i = 0; i < 2; i++) {
            var db = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("opennest_listing_" + i).withUsername("listing_" + i).withPassword("fabricated-listing-db-" + i);
            db.start(); databases.add(db);
            var cache = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "fabricated-listing-cache-" + i);
            cache.start(); redis.add(cache);
        }
        for (int i = 0; i < 2; i++) {
            var settings = settings(i);
            apps.add(new SpringApplication(OpenNestApplication.class, StorageConfiguration.class).run(settings.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new)));
        }
    }
    @AfterAll void close() {
        apps.reversed().forEach(ConfigurableApplicationContext::close);
        redis.reversed().forEach(GenericContainer::close);
        databases.reversed().forEach(PostgreSQLContainer::close);
    }
    @ParameterizedTest @ValueSource(strings = {"ENTIRE_FLAT", "ROOM", "PG_HOSTEL"})
    void draftUploadPublishInventoryPauseForEveryMode(String mode) throws Exception {
        UUID owner = owner(0, false);
        var listing = as(owner, () -> service().create(draft(mode, "Bengaluru", 10000)));
        UUID id = (UUID) listing.get("id");
        assertThat(listing).containsEntry("status", "PAUSED").containsEntry("version", 0L);
        assertThatThrownBy(() -> as(owner, () -> service().change(id, node("{\"version\":0}"), "publish")))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONTACT_VERIFICATION_REQUIRED));
        assertThatThrownBy(() -> service().detail(id)).isInstanceOf(RequestFailure.class);
        images(id, owner, mode);
        verifyContact(owner);
        var published = as(owner, () -> service().change(id, node("{\"version\":0}"), "publish"));
        assertThat(published).containsEntry("status", "ACTIVE").containsEntry("version", 1L);
        assertThat(as(owner, () -> service().change(id, node("{\"version\":1}"), "publish"))).containsEntry("version", 1L);
        var visible = service().detail(id);
        assertThat(visible).doesNotContainKeys("version", "privateAddress", "latitude", "longitude", "phoneNumber");
        assertThat(visible.get("rentBasis")).isEqualTo(mode.equals("PG_HOSTEL") ? "PER_PERSON" : mode.equals("ROOM") ? "PER_ROOM" : "PER_PROPERTY");
        var changed = as(owner, () -> service().change(id, inventory(mode, 1, false), "inventory"));
        assertThat(changed).containsEntry("status", "FULL_OCCUPIED").containsEntry("version", 2L);
        assertThatThrownBy(() -> as(owner, () -> service().change(id, inventory(mode, 1, false), "inventory"))).isInstanceOf(RequestFailure.class);
        assertThat(as(owner, () -> service().change(id, inventory(mode, 2, false), "inventory"))).containsEntry("version", 3L);
        as(owner, () -> service().change(id, node("{\"version\":3}"), "pause"));
        assertThat(as(owner, () -> service().change(id, inventory(mode, 4, true), "inventory"))).containsEntry("status", "PAUSED").containsEntry("version", 5L);
        assertThat(as(owner, () -> service().change(id, node("{\"version\":5}"), "pause"))).containsEntry("version", 5L);
    }
    @Test void publicationAndRetainedListingLimitsSerializeAcrossConnections() throws Exception {
        UUID owner = owner(0, true);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) {
            UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "Pune", 6000))).get("id");
            images(id, owner, "PG_HOSTEL"); ids.add(id);
        }
        assertThat(race(ids.stream().<java.util.concurrent.Callable<Boolean>>map(id -> () -> attempt(owner, () -> service().change(id, node("{\"version\":0}"), "publish"))).toList()))
                .containsExactlyInAnyOrder(true, true, true, true, true, false);
        assertThat(jdbc(0).queryForObject("select count(*) from properties where owner_id = ? and status <> 'PAUSED'", Long.class, owner)).isEqualTo(5L);
        UUID other = owner(0, false);
        var creates = new ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int i = 0; i < 24; i++) creates.add(() -> attempt(other, () -> service().create(draft("ROOM", "Delhi", 8000))));
        assertThat(race(creates).stream().filter(Boolean::booleanValue).count()).isEqualTo(20);
    }
    @Test void simultaneousInventoryWritesHaveOneWinner() throws Exception {
        UUID owner = owner(0, false);
        UUID id = (UUID) as(owner, () -> service().create(draft("ROOM", "Hyderabad", 5000))).get("id");
        var requests = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> attempt(owner, () -> service().change(id, inventory("ROOM", 0, true), "inventory")),
                () -> attempt(owner, () -> service().change(id, inventory("ROOM", 0, false), "inventory")));
        assertThat(race(requests)).containsExactlyInAnyOrder(true, false);
    }
    @Test void completionRetriesCannotAttachLaterStagingBytesAndCleanupPreservesAttachedObjects() throws Exception {
        UUID owner = owner(0, false);
        UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "Chennai", 7000))).get("id");
        byte[] bytes = png();
        var reservation = media().presign(id, owner, node(json.writeValueAsString(Map.of("segment", "ROOM", "contentType", "image/png", "byteSize", bytes.length))));
        var storage = apps.getFirst().getBean(ControlledStorage.class);
        storage.stage((String) reservation.get("objectKey"), bytes);
        var body = node(json.writeValueAsString(Map.of("imageId", reservation.get("imageId"))));
        var completed = media().complete(id, owner, body);
        assertThat(completed).containsEntry("contentType", "image/png");
        storage.stage((String) reservation.get("objectKey"), new byte[]{1,2,3});
        assertThat(media().complete(id, owner, body).get("imageId")).isEqualTo(completed.get("imageId"));
        String finalKey = jdbc(0).queryForObject("select object_key from property_images where id = ?", String.class, reservation.get("imageId"));
        assertThat(storage.finals.get(finalKey)).isNotEqualTo(new byte[]{1,2,3});
        storage.ageAll(); storage.finals.put("listings/orphan", bytes);
        media().cleanup();
        assertThat(storage.finals).containsKey(finalKey).doesNotContainKey("listings/orphan");
        assertThat(storage.staging).isEmpty();
    }
    @Test void malformedExpiredAndExcessUploadsCannotPublish() throws Exception {
        UUID owner = owner(0, true);
        UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "Kolkata", 6000))).get("id");
        var bad = media().presign(id, owner, node("{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":3}"));
        apps.getFirst().getBean(ControlledStorage.class).stage((String) bad.get("objectKey"), new byte[]{1,2,3});
        assertThatThrownBy(() -> media().complete(id, owner, node(json.writeValueAsString(Map.of("imageId", bad.get("imageId")))))).isInstanceOf(RequestFailure.class);
        assertThat(jdbc(0).queryForObject("select validation_state from property_images where id = ?", String.class, bad.get("imageId"))).isEqualTo("REJECTED");
        assertThatThrownBy(() -> as(owner, () -> service().change(id, node("{\"version\":0}"), "publish"))).isInstanceOf(RequestFailure.class);
        var reservations = new ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int i = 0; i < 15; i++) reservations.add(() -> attempt(owner, () -> media().presign(id, owner, node("{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":100}"))));
        assertThat(race(reservations).stream().filter(Boolean::booleanValue).count()).isEqualTo(12);
        UUID pending = jdbc(0).queryForObject("select id from property_images where property_id = ? and validation_state = 'PENDING' limit 1", UUID.class, id);
        jdbc(0).update("update property_images set created_at = current_timestamp - interval '2 hours', upload_expires_at = current_timestamp - interval '1 hour' where id = ?", pending);
        assertThatThrownBy(() -> media().complete(id, owner, node(json.writeValueAsString(Map.of("imageId", pending))))).isInstanceOf(RequestFailure.class);
    }
    @Test void allSortsFiltersGeoAndPaginationRunAgainstPostgisWithoutDuplicateCards() throws Exception {
        var ids = new ArrayList<UUID>();
        var modes = List.of("ENTIRE_FLAT", "ROOM", "PG_HOSTEL", "ROOM");
        var rents = List.of(9000, 5000, 5000, 12000);
        var created = List.of("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z");
        var latitudes = List.of(12.9717, 12.9718, 12.9718, 12.9720);
        for (int index = 0; index < modes.size(); index++) {
            UUID owner = owner(0, true);
            String mode = modes.get(index); int rent = rents.get(index);
            UUID id = (UUID) as(owner, () -> service().create(draft(mode, "SearchCity", rent))).get("id");
            images(id, owner, mode); as(owner, () -> service().change(id, node("{\"version\":0}"), "publish"));
            jdbc(0).update("update properties set created_at=?, latitude=? where id=?", Timestamp.from(Instant.parse(created.get(index))), latitudes.get(index), id);
            if (index < 3) jdbc(0).update("update users set review_count=3,average_rating=? where id=?", index == 2 ? 2.0 : 4.8, owner);
            if (index == 2) jdbc(0).update("update properties set male_rooms_available=3 where id=?", id);
            ids.add(id);
        }
        var tie23 = ordered(ids.get(1), ids.get(2)); var tie24 = ordered(ids.get(1), ids.get(3)); var tie12 = ordered(ids.get(0), ids.get(1));
        var expected = Map.of(
                "NEWEST", List.of(ids.get(3), tie23.get(0), tie23.get(1), ids.get(0)),
                "RENT_ASC", List.of(tie23.get(0), tie23.get(1), ids.get(0), ids.get(3)),
                "RENT_DESC", List.of(ids.get(3), ids.get(0), tie23.get(0), tie23.get(1)),
                "ROOMS_DESC", List.of(ids.get(2), tie24.get(0), tie24.get(1), ids.get(0)),
                "RATING_DESC", List.of(tie12.get(0), tie12.get(1), ids.get(2), ids.get(3)),
                "DISTANCE_ASC", List.of(ids.get(0), tie23.get(0), tie23.get(1), ids.get(3)));
        for (String sort : List.of("NEWEST", "RENT_ASC", "RENT_DESC", "ROOMS_DESC", "RATING_DESC", "DISTANCE_ASC")) {
            var query = query("city", " searchcity ", "size", "2", "sort", sort, "lat", "12.9716", "lng", "77.5946", "radiusKm", "1");
            var first = service().search(query, false);
            assertThat(first).containsEntry("totalElements", 4L).containsEntry("totalPages", 2L).containsEntry("hasNext", true).containsEntry("sort", sort);
            assertThat(itemIds(first)).as(sort + " first page").containsExactlyElementsOf(expected.get(sort).subList(0, 2));
            query.set("page", "1");
            var last = service().search(query, false);
            assertThat(itemIds(last)).as(sort + " second page").containsExactlyElementsOf(expected.get(sort).subList(2, 4));
            assertThat(last).containsEntry("hasPrevious", true).containsEntry("hasNext", false);
        }
        assertThat(service().search(query("city", "SearchCity", "minBedrooms", "3", "maxBedrooms", "3", "hasWifi", "true"), false)).containsEntry("totalElements", 3L);
        assertThat(service().search(query("city", "SearchCity", "minTotalRooms", "1", "genderRoomAvailable", "MALE", "maxSharingPerRoom", "2"), false)).containsEntry("totalElements", 3L);
        assertThat(service().search(query("city", "SearchCity", "hasWifi", "false"), false)).containsEntry("totalElements", 0L);
        assertThat(service().search(query("city", "SearchCity", "page", "500", "size", "20"), false)).containsEntry("totalElements", 4L);
        assertThat((Long) service().search(query(), false).get("totalElements")).isGreaterThanOrEqualTo(3L);
        assertThat(jdbc(0).queryForList("explain select id from properties where status='ACTIVE' order by created_at desc,id asc limit 20")).isNotEmpty();
        assertThat(jdbc(0).queryForList("explain select id from properties where status='ACTIVE' and city='SEARCHCITY' and monthly_rent between 5000 and 15000 order by monthly_rent asc,id asc limit 20")).isNotEmpty();
    }
    private List<UUID> ordered(UUID... ids) { return Arrays.stream(ids).sorted(Comparator.comparing(UUID::toString)).toList(); }
    private List<UUID> itemIds(Map<String, Object> page) {
        return ((List<?>) page.get("items")).stream().map(item -> (UUID) ((Map<?, ?>) item).get("id")).toList();
    }
    @Test void browserSecurityPrivacyStrictJsonAndSandboxReplay() throws Exception {
        UUID owner = owner(0, false);
        String cookie = session(0, owner, "production:primary");
        var csrf = json.readTree(send(0, "GET", "/api/v1/auth/csrf", cookie, null, null).body()).get("token").asString();
        String payload = json.writeValueAsString(draft("ROOM", "Mumbai", 9000));
        var response = send(0, "POST", "/api/v1/listings", cookie, csrf, payload);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(response.body()).get("id").asString());
        assertThat(send(0, "GET", "/api/v1/listings/" + id, null, null, null).statusCode()).isEqualTo(404);
        assertThat(send(0, "GET", "/api/v1/listings/mine", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send(0, "POST", "/api/v1/listings", cookie, null, payload).statusCode()).isEqualTo(403);
        assertThat(send(0, "POST", "/api/v1/listings", cookie, csrf, "{\"title\":\"a\",\"title\":\"b\"}").statusCode()).isEqualTo(400);
        assertThat(send(0, "POST", "/api/v1/listings", cookie, csrf, " ".repeat(32769)).statusCode()).isEqualTo(413);
        var denied = send(0, "POST", "/api/v1/listings/" + id + "/publish", cookie, csrf, "{\"version\":0}");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("separately verified +91").doesNotContain("private_address", "SessionPrincipal");
        String replay = session(0, owner, "development:sandbox");
        assertThat(send(0, "GET", "/api/v1/listings/mine", replay, null, null).statusCode()).isEqualTo(401);
        assertThat(send(1, "GET", "/api/v1/listings/" + id, null, null, null).statusCode()).isEqualTo(404);
        assertThat(send(0, "GET", "/api/v1/listings/search?city=Mumbai", cookie, null, null).headers().firstValue("Cache-Control")).contains("no-store");
        assertThatThrownBy(() -> as(owner(0, false), () -> service().change(id, inventory("ROOM", 0, true), "inventory"))).isInstanceOf(RequestFailure.class);
    }
    @Test void migrationUpgradePreservesOriginalChecksumsAndValidatesMediaMetadata() {
        assertThat(jdbc(0).queryForList("select version from flyway_schema_history where success order by installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(jdbc(0).queryForList("select column_name from information_schema.columns where table_name='listing_owner_profiles'", String.class))
                .containsExactlyInAnyOrder("id", "full_name", "average_rating", "review_count", "broker_report_count");
        UUID owner = owner(0, false);
        UUID id = (UUID) as(owner, () -> service().create(draft("ROOM", "Jaipur", 5000))).get("id");
        var row = media().presign(id, owner, node("{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":10}"));
        assertThatThrownBy(() -> jdbc(0).update("update property_images set declared_content_type = null where id = ?", row.get("imageId")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void repeatableReadKeepsItemsAndCountOnTheSameSnapshot() throws Exception {
        UUID owner = owner(0, true);
        UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "SnapshotCity", 7000))).get("id");
        images(id, owner, "PG_HOSTEL"); as(owner, () -> service().change(id, node("{\"version\":0}"), "publish"));
        var store = apps.getFirst().getBean(ListingStore.class);
        var result = store.read(() -> {
            assertThat(store.jdbc().queryForObject("select status from properties where id = ?", String.class, id)).isEqualTo("ACTIVE");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> jdbc(0).update("update properties set status='PAUSED' where id = ?", id)).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
            return service().search(query("city", "SnapshotCity"), false);
        });
        assertThat(result).containsEntry("totalElements", 1L);
        assertThat((List<?>) result.get("items")).hasSize(1);
        assertThat(service().search(query("city", "SnapshotCity"), false)).containsEntry("totalElements", 0L);
    }
    @Test void standaloneSearchOpensItsOwnSnapshotBeforeConcurrentCountAndPageUpdate() throws Exception {
        UUID owner = owner(0, true);
        UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "StandaloneSnapshotCity", 7000))).get("id");
        images(id, owner, "PG_HOSTEL"); as(owner, () -> service().change(id, node("{\"version\":0}"), "publish"));
        var jdbc = apps.getFirst().getBean(SnapshotJdbc.class);
        var committed = new CountDownLatch(1);
        jdbc.afterCount.set(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> {
                    jdbc(0).update("update properties set status='PAUSED' where id=?", id); committed.countDown();
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        Map<String, Object> result;
        try { result = service().search(query("city", "StandaloneSnapshotCity"), false); }
        finally { jdbc.afterCount.set(null); }
        assertThat(committed.getCount()).isZero();
        assertThat(result).containsEntry("totalElements", 1L);
        assertThat(itemIds(result)).containsExactly(id);
        assertThat(service().search(query("city", "StandaloneSnapshotCity"), false)).containsEntry("totalElements", 0L);
    }
    @Test void googleDisabledBrowsingHasExactOriginCorsAndNoAuthenticatedRoutes() throws Exception {
        var configuration = settings(0); configuration.put("OPENNEST_TELEGRAM_AUTH_ENABLED", "false");
        try (var application = new SpringApplication(OpenNestApplication.class).run(configuration.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))) {
            String origin = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
            var allowed = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/listings/search"))
                    .header("Origin", "https://opennest.example.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(allowed.statusCode()).isEqualTo(200);
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
            assertThat(allowed.headers().firstValue("Cache-Control")).contains("no-store");
            var blocked = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/listings/search"))
                    .header("Origin", "https://untrusted.example.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(blocked.statusCode()).isEqualTo(403);
            assertThat(blocked.body()).contains("about:blank");
            assertThat(http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/listings/mine")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }
    @Test void encodedListingPathKeepsLimitsAndCorsAheadOfLaterSecurityFilter() throws Exception {
        var configuration = settings(0);
        configuration.put("spring.security.filter.order", "-80"); configuration.put("server.servlet.context-path", "/context");
        try (var application = new SpringApplication(OpenNestApplication.class, StorageConfiguration.class).run(configuration.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))) {
            String origin = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
            for (String path : List.of("/context/api/v1/listings", "/context/%61pi/v1/listings")) {
                var duplicate = http.send(HttpRequest.newBuilder(URI.create(origin + path)).header("Origin", "https://opennest.example.invalid")
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"a\",\"title\":\"b\"}")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(duplicate.statusCode()).isEqualTo(400);
                assertThat(duplicate.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
                assertThat(json.readTree(duplicate.body()).get("detail").asString()).isEqualTo("The request is invalid.");
                var oversized = http.send(HttpRequest.newBuilder(URI.create(origin + path)).header("Origin", "https://opennest.example.invalid")
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(" ".repeat(32769))).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(oversized.statusCode()).isEqualTo(413);
                assertThat(oversized.headers().firstValue("Access-Control-Allow-Origin")).contains("https://opennest.example.invalid");
            }
        }
    }
    @Test void upgradesPopulatedV2WithoutChangingHistoricalMigrations() throws Exception {
        jdbc(0).execute("create database opennest_listing_upgrade");
        var db = databases.getFirst();
        var datasource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/opennest_listing_upgrade", db.getUsername(), db.getPassword());
        var old = org.flywaydb.core.Flyway.configure().dataSource(datasource).target("2").load(); old.migrate();
        var jdbc = new JdbcTemplate(datasource);
        var checksums = jdbc.queryForList("select checksum from flyway_schema_history where version in ('1','2') order by installed_rank", Integer.class);
        UUID owner = UUID.randomUUID(), property = UUID.randomUUID(), image = UUID.randomUUID();
        jdbc.update("insert into users(id,full_name) values (?, 'Fabricated upgrade owner')", owner);
        jdbc.update("insert into properties(id,owner_id,title,description,category,city,locality,private_address,latitude,longitude,monthly_rent,security_deposit,male_rooms_available,female_rooms_available,shared_rooms_available,max_sharing_per_room) values (?,?,'Upgrade','Fabricated upgrade','PG_HOSTEL','PUNE','TEST','Private upgrade address',18.52,73.85,5000,0,1,0,0,2)", property, owner);
        jdbc.update("insert into property_images(id,property_id,upload_object_key,segment,upload_expires_at) values (?,?,'upgrade/staging','ROOM',current_timestamp + interval '10 minutes')", image, property);
        org.flywaydb.core.Flyway.configure().dataSource(datasource).load().migrate();
        assertThat(jdbc.queryForList("select checksum from flyway_schema_history where version in ('1','2') order by installed_rank", Integer.class)).isEqualTo(checksums);
        assertThat(jdbc.queryForObject("select count(*) from property_images where id=? and declared_byte_size is null", Long.class, image)).isEqualTo(1L);
        var legacySettings = settings(0); legacySettings.put("DB_NAME", "opennest_listing_upgrade");
        try (var legacy = new SpringApplication(OpenNestApplication.class, StorageConfiguration.class).run(legacySettings.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))) {
            var body = node(json.writeValueAsString(Map.of("imageId", image)));
            assertThatThrownBy(() -> legacy.getBean(ListingMedia.class).complete(property, owner, body))
                    .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONFLICT));
            String cookie = savedSession(legacy.getBean(RedisSessionRepository.class), 0, owner, "production:primary");
            String origin = "http://127.0.0.1:" + legacy.getEnvironment().getProperty("local.server.port");
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/csrf")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            String csrf = json.readTree(csrfResponse.body()).get("token").asString();
            var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/listings/" + property + "/images/complete"))
                    .header("Cookie", cookie).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("detail").asString()).isEqualTo("The request conflicts with the current state.");
        }
        assertThat(org.flywaydb.core.Flyway.configure().dataSource(datasource).load().migrate().migrationsExecuted).isZero();
    }
    @Test void reservationRateSurvivesExpiredUploadSlots() {
        UUID owner = owner(0, false);
        UUID id = (UUID) as(owner, () -> service().create(draft("PG_HOSTEL", "Lucknow", 5000))).get("id");
        for (int i = 0; i < 60; i++) {
            var reserved = media().presign(id, owner, node("{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":100}"));
            jdbc(0).update("update property_images set created_at=current_timestamp - interval '2 hours', upload_expires_at=current_timestamp - interval '1 hour' where id=?", reserved.get("imageId"));
        }
        assertThatThrownBy(() -> media().presign(id, owner, node("{\"segment\":\"ROOM\",\"contentType\":\"image/png\",\"byteSize\":100}")))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.RATE_LIMITED));
    }
    @Test void recordsRepresentativeMultiCityQueryPlans() throws Exception {
        UUID owner = owner(0, false);
        jdbc(0).update("""
                insert into properties(id,owner_id,title,description,category,status,city,locality,private_address,latitude,longitude,
                    monthly_rent,security_deposit,male_rooms_available,female_rooms_available,shared_rooms_available,max_sharing_per_room)
                select gen_random_uuid(),?,'Fabricated plan listing','Synthetic query-plan data','PG_HOSTEL','ACTIVE',
                    (array['BENGALURU','DELHI','MUMBAI','KOLKATA','JAIPUR'])[1+(n%5)],'PLAN LOCALITY','Private synthetic address',
                    12.9716,77.5946,4000+(n%25000),0,1,0,0,2 from generate_series(1,3000) n
                """, owner);
        jdbc(0).execute("analyze properties");
        var plans = new ArrayList<String>();
        for (String query : List.of("select id from properties where status='ACTIVE' order by created_at desc,id asc limit 20",
                "select id from properties where status='ACTIVE' and city='BENGALURU' and monthly_rent between 5000 and 10000 order by monthly_rent asc,id asc limit 20",
                "select id from properties where status='ACTIVE' and ST_DWithin(location,ST_SetSRID(ST_MakePoint(77.5946,12.9716),4326)::geography,2500) order by created_at desc,id asc limit 20")) {
            plans.add(query); plans.addAll(jdbc(0).queryForList("explain (analyze,buffers) " + query, String.class));
        }
        var output = java.nio.file.Path.of(System.getProperty("basedir", "."), "target", "listing-query-plans.txt");
        java.nio.file.Files.createDirectories(output.getParent()); java.nio.file.Files.write(output, plans);
        assertThat(plans).anyMatch(line -> line.contains("Index Scan") || line.contains("Bitmap"));
    }
    private ListingService service() { return apps.getFirst().getBean(ListingService.class); }
    private ListingMedia media() { return apps.getFirst().getBean(ListingMedia.class); }
    private JdbcTemplate jdbc(int target) { return apps.get(target).getBean(JdbcTemplate.class); }
    private UUID owner(int target, boolean verified) {
        UUID id = UUID.randomUUID(); jdbc(target).update("insert into users (id,full_name) values (?,?)", id, "Fabricated listing owner");
        if (verified) verifyContact(id); return id;
    }
    private void verifyContact(UUID owner) {
        long suffix = jdbc(0).queryForObject("select count(*) from users where phone_number is not null", Long.class);
        jdbc(0).update("update users set phone_number = ?, phone_verified_at = current_timestamp where id = ?", "+910" + String.format("%09d", suffix), owner);
    }
    private <T> T as(UUID owner, Supplier<T> operation) {
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(owner, "production:primary", Instant.now()), null, List.of())));
        try { return operation.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
    private boolean attempt(UUID owner, Supplier<?> operation) {
        try { as(owner, operation); return true; } catch (RequestFailure failure) { assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.CONFLICT); return false; }
    }
    private List<Boolean> race(List<Callable<Boolean>> operations) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            operations.forEach(operation -> futures.add(executor.submit(() -> { start.await(); return operation.call(); })));
            start.countDown(); var result = new ArrayList<Boolean>();
            for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS)); return result;
        }
    }
    private JsonNode draft(String mode, String city, int rent) {
        var result = new LinkedHashMap<String, Object>();
        result.putAll(Map.of("title", "Fabricated OpenNest listing", "description", "Fabricated accommodation for integration checks", "city", city,
                "locality", "Test Locality", "privateAddress", "Private fabricated address", "latitude", 12.9716, "longitude", 77.5946,
                "monthlyRent", rent, "securityDeposit", 1000));
        result.putAll(Map.of("category", mode.equals("PG_HOSTEL") ? "PG_HOSTEL" : "FLAT", "hasWifi", true, "hasGym", false, "hasSwimmingPool", false));
        if (!mode.equals("PG_HOSTEL")) { result.put("flatRentalMode", mode); result.put("bedrooms", 3); }
        if (mode.equals("ENTIRE_FLAT")) result.putAll(Map.of("wholeFlatAvailable", true, "maxOccupants", 4));
        else result.putAll(Map.of("maleRoomsAvailable", 1, "femaleRoomsAvailable", 0, "sharedRoomsAvailable", 1, "maxSharingPerRoom", 2));
        return node(json.writeValueAsString(result));
    }
    private JsonNode inventory(String mode, long version, boolean available) {
        return node(json.writeValueAsString(mode.equals("ENTIRE_FLAT") ? Map.of("version", version, "wholeFlatAvailable", available, "maxOccupants", 4)
                : Map.of("version", version, "maleRoomsAvailable", available ? 1 : 0, "femaleRoomsAvailable", 0, "sharedRoomsAvailable", 0, "maxSharingPerRoom", 2)));
    }
    private JsonNode node(String value) { return json.readTree(value); }
    private LinkedMultiValueMap<String, String> query(String... values) {
        var result = new LinkedMultiValueMap<String, String>(); for (int i = 0; i < values.length; i += 2) result.add(values[i], values[i + 1]); return result;
    }
    private void images(UUID id, UUID owner, String mode) throws Exception {
        for (String segment : mode.equals("PG_HOSTEL") ? List.of("ROOM", "BATHROOM") : List.of("ROOM", "HALL", "BATHROOM", "KITCHEN")) {
            byte[] bytes = png();
            var reserved = media().presign(id, owner, node(json.writeValueAsString(Map.of("segment", segment, "contentType", "image/png", "byteSize", bytes.length))));
            apps.getFirst().getBean(ControlledStorage.class).stage((String) reserved.get("objectKey"), bytes);
            media().complete(id, owner, node(json.writeValueAsString(Map.of("imageId", reserved.get("imageId")))));
        }
    }
    private byte[] png() throws Exception { var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", output); return output.toByteArray(); }
    private String session(int target, UUID owner, String realm) {
        return savedSession(apps.get(target).getBean(RedisSessionRepository.class), target, owner, realm);
    }
    private <S extends org.springframework.session.Session> String savedSession(org.springframework.session.SessionRepository<S> repository, int target, UUID owner, String realm) {
        S session = repository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(owner, realm, Instant.now()), null, List.of())));
        repository.save(session);
        return (target == 0 ? "__Host-OPENNEST_SESSION=" : "OPENNEST_SANDBOX_SESSION=") + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> send(int target, String method, String path, String cookie, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apps.get(target).getEnvironment().getProperty("local.server.port") + path)).timeout(Duration.ofSeconds(30));
        if (cookie != null) request.header("Cookie", cookie); if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<String, String> settings(int target) {
        var result = new LinkedHashMap<String, String>();
        result.putAll(Map.of("server.port", "0", "server.address", "127.0.0.1", "OPENNEST_ENVIRONMENT", target == 0 ? "PRODUCTION" : "DEVELOPMENT",
                "OPENNEST_DATA_MODE", target == 0 ? "PRIMARY" : "SANDBOX", "OPENNEST_TELEGRAM_AUTH_ENABLED", target == 0 ? "true" : "false", "OPENNEST_BACKEND_ORIGIN", "https://api.opennest.example.invalid",
                "OPENNEST_FRONTEND_ORIGIN", "https://opennest.example.invalid"));
        result.put("TELEGRAM_BOT_USERNAME", "OpenNestFixtureBot");
        result.put("TELEGRAM_BOT_TOKEN", "1234567:AAAAAAAAAAAAAAAAAAAAAAAAAA");
        result.put("TELEGRAM_OTP_HMAC_KEY", "fabricated-listing-hmac-secret-at-least-32");
        result.put("TELEGRAM_WEBHOOK_SECRET", "fabricated-listing-hook-secret-at-least-32");
        for (int i = 0; i < 2; i++) {
            String prefix = i == 0 ? "" : "SANDBOX_";
            result.put(prefix + "DB_HOST", databases.get(i).getHost()); result.put(prefix + "DB_PORT", databases.get(i).getMappedPort(5432).toString());
            result.put(prefix + "DB_NAME", databases.get(i).getDatabaseName()); result.put(prefix + "DB_USER", databases.get(i).getUsername());
            result.put(prefix + "DB_PASSWORD", i == target ? databases.get(i).getPassword() : "");
            result.put(prefix + "REDIS_HOST", redis.get(i).getHost()); result.put(prefix + "REDIS_PORT", redis.get(i).getMappedPort(6379).toString());
            result.put(prefix + "REDIS_PASSWORD", i == target ? "fabricated-listing-cache-" + i : "");
        }
        return result;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean @Primary ControlledStorage controlledStorage() { return new ControlledStorage(); }
        @Bean @Primary SnapshotJdbc snapshotJdbc(javax.sql.DataSource dataSource) { return new SnapshotJdbc(dataSource); }
    }
    static class SnapshotJdbc extends JdbcTemplate {
        final java.util.concurrent.atomic.AtomicReference<Runnable> afterCount = new java.util.concurrent.atomic.AtomicReference<>();
        SnapshotJdbc(javax.sql.DataSource dataSource) { super(dataSource); }
        @Override public <T> T query(org.springframework.jdbc.core.PreparedStatementCreator creator,
                org.springframework.jdbc.core.PreparedStatementSetter setter, org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
            T result = super.query(creator, setter, extractor);
            if (creator instanceof org.springframework.jdbc.core.SqlProvider sql && sql.getSql().startsWith("select count(*) from properties p join listing_owner_profiles")) {
                var callback = afterCount.getAndSet(null); if (callback != null) callback.run();
            }
            return result;
        }
    }
    static class ControlledStorage implements StorageObjects {
        final Map<String, byte[]> staging = new ConcurrentHashMap<>(), finals = new ConcurrentHashMap<>();
        private Instant modified = Instant.now();
        public boolean enabled() { return true; }
        public Upload upload(String key, String type, long size) { return new Upload("https://storage.example.invalid/" + key, Map.of("Content-Type", type)); }
        void stage(String key, byte[] bytes) { staging.put(key, bytes); }
        public byte[] read(String key, long size) { var bytes = staging.get(key); ListingInput.require(bytes != null && bytes.length == size); return bytes; }
        public void writeFinal(String key, byte[] bytes, String type) { if (finals.putIfAbsent(key, bytes) != null) throw new IllegalStateException(); }
        public String download(String key) { return "https://media.example.invalid/" + key; }
        public void delete(Bucket bucket, String key) { (bucket == Bucket.STAGING ? staging : finals).remove(key); }
        public void visit(Bucket bucket, java.util.function.Consumer<Stored> visitor) { List.copyOf((bucket == Bucket.STAGING ? staging : finals).keySet()).forEach(key -> visitor.accept(new Stored(key, modified))); }
        void ageAll() { modified = Instant.now().minus(Duration.ofDays(2)); }
    }
}

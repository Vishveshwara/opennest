package in.opennest;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.EntityManagerFactory;
import in.opennest.identity.internal.persistence.IdentityPersistenceScenarios;
import in.opennest.listing.internal.persistence.ListingPersistenceScenarios;
import in.opennest.interaction.internal.persistence.InteractionPersistenceScenarios;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaMigrationIT {

    private final List<PostgreSQLContainer> containers = new ArrayList<>();
    private final List<Database> databases = new ArrayList<>();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<String[]> startupArguments = new ArrayList<>();

    @BeforeAll
    void migrateBothDeploymentTargetsThroughApplicationStartup() throws Exception {
        try {
            databases.add(database("PRIMARY", "opennest_schema_test", "opennest_schema_user", "fabricated-primary-schema-secret"));
            databases.add(database("SANDBOX", "opennest_sandbox_schema_test", "opennest_sandbox_schema_user", "fabricated-sandbox-schema-secret"));
            assertThat(databases.get(0).host() + ":" + databases.get(0).port())
                    .isNotEqualTo(databases.get(1).host() + ":" + databases.get(1).port());
            for (var database : databases) {
                try (var connection = database.connect()) {
                    assertThat(value(connection, "SHOW server_version_num").toString()).startsWith("17");
                    assertThat(value(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('users','auth_identities','properties','property_images','contact_reveals','owner_reviews')"))
                            .as("Integration databases must be fresh; this suite never cleans or drops a schema").isEqualTo(0L);
                }
                var application = new SpringApplication(OpenNestApplication.class);
                var arguments = new ArrayList<String>();
                arguments.add("--OPENNEST_ENVIRONMENT=DEVELOPMENT");
                arguments.add("--server.port=0");
                arguments.add("--OPENNEST_DATA_MODE=" + database.mode());
                for (var target : databases) {
                    var prefix = target.mode().equals("PRIMARY") ? "DB_" : "SANDBOX_DB_";
                    arguments.add("--" + prefix + "HOST=" + target.host());
                    arguments.add("--" + prefix + "PORT=" + target.port());
                    arguments.add("--" + prefix + "NAME=" + target.name());
                    arguments.add("--" + prefix + "USER=" + target.user());
                    arguments.add("--" + prefix + "PASSWORD=" + target.password());
                }
                arguments.add("--OPENNEST_DEVELOPMENT_AUTH=false");
                arguments.add("--OPENNEST_PERSONA_SWITCHING=false");
                arguments.add("--OPENNEST_DEMO_RESET=false");
                arguments.add("--OPENNEST_DEMO_TELEMETRY=false");
                {
                    startupArguments.add(arguments.toArray(String[]::new));
                    var context = application.run(arguments.toArray(String[]::new));
                    contexts.add(context);
                    var flyway = context.getBean(Flyway.class);
                    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
                    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                    assertThat(flyway.migrate().migrationsExecuted).isZero();
                    assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
                    assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
                    var factory = context.getBean(EntityManagerFactory.class);
                    assertThat(factory.getMetamodel().getEntities()).hasSize(6);
                    assertThat(factory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
                }
            }
        } catch (Exception | AssertionError exception) {
            stopContainers();
            throw exception;
        }
    }

    @AfterAll
    void stopContainers() {
        contexts.reversed().forEach(ConfigurableApplicationContext::close);
        containers.forEach(PostgreSQLContainer::stop);
    }

    @TestFactory
    Stream<DynamicNode> persistenceRoundTrips() {
        return java.util.stream.IntStream.range(0, contexts.size()).mapToObj(index -> {
            var factory = contexts.get(index).getBean(EntityManagerFactory.class);
            return dynamicContainer(databases.get(index).mode() + " persistence", Stream.of(
                    IdentityPersistenceScenarios.tests(factory), ListingPersistenceScenarios.tests(factory),
                    InteractionPersistenceScenarios.tests(factory)).flatMap(List::stream));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void failsStartupOnSchemaDriftWithoutRepairingIt(int target) throws Exception {
        try (var connection = databases.get(target).connect()) {
            execute(connection, "ALTER TABLE public.auth_identities RENAME COLUMN subject TO missing_subject");
            try {
                assertThatThrownBy(() -> {
                    try (var ignored = new SpringApplication(OpenNestApplication.class).run(startupArguments.get(target))) {
                        throw new AssertionError("Startup must reject schema drift");
                    }
                }).hasStackTraceContaining("Schema validation: missing column");
                assertThat(value(connection, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='auth_identities' AND column_name='subject'")).isEqualTo(0L);
            } finally {
                execute(connection, "ALTER TABLE public.auth_identities RENAME COLUMN missing_subject TO subject");
            }
        }
    }

    @Test
    void installsIdenticalSchemaAndChecksumsWithoutApplicationFixtures() throws Exception {
        String fingerprint = null;
        List<String> history = null;
        for (var database : databases) {
            try (var connection = database.connect()) {
                assertThat(value(connection, "SELECT public.PostGIS_Lib_Version()").toString()).startsWith("3.5.");
                var tables = strings(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'spatial_ref_sys' ORDER BY table_name");
                assertThat(tables).containsExactly("auth_identities", "contact_reveals", "flyway_schema_history", "owner_reviews", "properties", "property_images", "users");
                for (String table : tables) {
                    if (!table.equals("flyway_schema_history")) {
                        assertThat(value(connection, "SELECT count(*) FROM public." + table)).isEqualTo(0L);
                    }
                }
                String current = String.join("\n", strings(connection, """
                        SELECT c.relname || ':' || a.attname || ':' || format_type(a.atttypid,a.atttypmod)
                            || ':' || a.attnotnull || ':' || a.attgenerated::text || ':' || COALESCE(pg_get_expr(d.adbin,d.adrelid),'')
                        FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace
                        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                        WHERE n.nspname='public' AND c.relname IN ('users','auth_identities','properties','property_images','contact_reveals','owner_reviews')
                            AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.relname,a.attnum
                        """));
                current += String.join("\n", strings(connection, "SELECT conrelid::regclass::text || ':' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname ~ '^(pk_|uq_|fk_|ck_)' ORDER BY conname"));
                current += String.join("\n", strings(connection, "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname LIKE 'ix_%' ORDER BY indexname"));
                var currentHistory = strings(connection, "SELECT version || ':' || checksum || ':' || success FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
                assertThat(currentHistory).hasSize(2);
                if (fingerprint != null) {
                    assertThat(current).isEqualTo(fingerprint);
                    assertThat(currentHistory).isEqualTo(history);
                }
                fingerprint = current;
                history = currentHistory;
                assertThat(value(connection, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('users','auth_identities','properties','property_images','contact_reveals','owner_reviews')")).isEqualTo(70L);
                assertThat(value(connection, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND is_generated='ALWAYS'")).isEqualTo(3L);
                assertThat(value(connection, "SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='f' AND confdeltype='r' AND confupdtype='r' AND NOT condeferrable")).isEqualTo(7L);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void preservesDraftDefaultsAndSupportsEveryPublishedInventoryBranch(int target) throws Exception {
        fixture(target, f -> {
            assertThat(value(f.db(), "SELECT status || ':' || version || ':' || has_wifi || ':' || nearby_landmarks FROM properties WHERE id=?", f.entire())).isEqualTo("PAUSED:0:false:[]");
            assertThat(value(f.db(), "SELECT total_rooms_available FROM properties WHERE id=?", f.entire())).isNull();
            for (var id : List.of(f.entire(), f.room(), f.pg())) {
                execute(f.db(), "UPDATE properties SET status='ACTIVE' WHERE id=?", id);
            }
            execute(f.db(), "UPDATE properties SET whole_flat_available=false,status='FULL_OCCUPIED' WHERE id=?", f.entire());
            for (var id : List.of(f.room(), f.pg())) {
                execute(f.db(), "UPDATE properties SET male_rooms_available=0,female_rooms_available=0,shared_rooms_available=0,status='FULL_OCCUPIED' WHERE id=?", id);
            }
            assertThat(value(f.db(), "SELECT count(*) FROM properties WHERE status='FULL_OCCUPIED'")).isEqualTo(3L);
            execute(f.db(), "UPDATE properties SET status='PAUSED' WHERE id=?", f.entire());
            execute(f.db(), "UPDATE properties SET max_occupants=11,bedrooms=0 WHERE id=?", f.entire());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rejectsMissingOrInapplicableModeInventoryWithoutNullLeaks(int target) throws Exception {
        fixture(target, f -> {
            for (String change : List.of("flat_rental_mode=NULL", "bedrooms=NULL", "bedrooms=-1", "whole_flat_available=NULL", "max_occupants=NULL", "max_occupants=0", "male_rooms_available=0", "female_rooms_available=0", "shared_rooms_available=0", "max_sharing_per_room=1")) {
                reject(f.db(), "23514", "UPDATE properties SET " + change + " WHERE id=?", f.entire());
            }
            for (var id : List.of(f.room(), f.pg())) {
                for (String change : List.of("male_rooms_available=NULL", "female_rooms_available=NULL", "shared_rooms_available=NULL", "max_sharing_per_room=NULL", "male_rooms_available=-1", "female_rooms_available=-1", "shared_rooms_available=-1", "max_sharing_per_room=0", "max_sharing_per_room=11", "whole_flat_available=false", "max_occupants=2")) {
                    reject(f.db(), "23514", "UPDATE properties SET " + change + " WHERE id=?", id);
                }
            }
            reject(f.db(), "23514", "UPDATE properties SET flat_rental_mode=NULL WHERE id=?", f.room());
            reject(f.db(), "23514", "UPDATE properties SET bedrooms=NULL WHERE id=?", f.room());
            reject(f.db(), "23514", "UPDATE properties SET bedrooms=0 WHERE id=?", f.pg());
            reject(f.db(), "23514", "UPDATE properties SET flat_rental_mode='ROOM' WHERE id=?", f.pg());
            reject(f.db(), "23514", "UPDATE properties SET status='FULL_OCCUPIED' WHERE id=?", f.entire());
            execute(f.db(), "UPDATE properties SET male_rooms_available=0,female_rooms_available=0,shared_rooms_available=0 WHERE id=?", f.room());
            reject(f.db(), "23514", "UPDATE properties SET status='ACTIVE' WHERE id=?", f.room());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void enforcesListingRangesRequiredValuesAndTimestamps(int target) throws Exception {
        fixture(target, f -> {
            for (String change : List.of("monthly_rent=0", "security_deposit=-1", "version=-1", "city=' '", "locality=''", "title=''", "description=''", "private_address=' '", "category='UNKNOWN'", "status='UNKNOWN'", "flat_rental_mode='UNKNOWN'", "latitude=6.499999", "latitude=37.500001", "longitude=67.999999", "longitude=97.500001", "nearby_landmarks='{}'::jsonb", "updated_at=created_at-interval '1 second'")) {
                reject(f.db(), "23514", "UPDATE properties SET " + change + " WHERE id=?", f.entire());
            }
            for (String column : List.of("owner_id", "title", "description", "category", "status", "version", "city", "locality", "private_address", "latitude", "longitude", "monthly_rent", "security_deposit", "has_wifi", "has_gym", "has_swimming_pool", "nearby_landmarks", "created_at", "updated_at")) {
                reject(f.db(), "23502", "UPDATE properties SET " + column + "=NULL WHERE id=?", f.entire());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void generatesLargeRoomTotalsAndRejectsAssignments(int target) throws Exception {
        fixture(target, f -> {
            execute(f.db(), "UPDATE properties SET male_rooms_available=2147483647,female_rooms_available=2147483647,shared_rooms_available=2147483647 WHERE id=?", f.room());
            assertThat(value(f.db(), "SELECT total_rooms_available FROM properties WHERE id=?", f.room())).isEqualTo(6442450941L);
            execute(f.db(), "UPDATE properties SET male_rooms_available=0 WHERE id=?", f.room());
            assertThat(value(f.db(), "SELECT total_rooms_available FROM properties WHERE id=?", f.room())).isEqualTo(4294967294L);
            reject(f.db(), "428C9", "UPDATE properties SET total_rooms_available=0 WHERE id=?", f.room());
            reject(f.db(), "428C9", "UPDATE properties SET location=ST_SetSRID(ST_MakePoint(77,13),4326)::geography WHERE id=?", f.entire());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void generatesCorrectGeographyAndUsesMetresAtCoordinateEdges(int target) throws Exception {
        fixture(target, f -> {
            assertThat(value(f.db(), "SELECT ST_X(location::geometry)=longitude::float8 AND ST_Y(location::geometry)=latitude::float8 AND ST_SRID(location::geometry)=4326 FROM properties WHERE id=?", f.entire())).isEqualTo(true);
            assertThat(value(f.db(), "SELECT ST_DWithin(location,ST_SetSRID(ST_MakePoint(longitude::float8+0.001,latitude::float8),4326)::geography,150) AND NOT ST_DWithin(location,ST_SetSRID(ST_MakePoint(longitude::float8+0.001,latitude::float8),4326)::geography,50) FROM properties WHERE id=?", f.entire())).isEqualTo(true);
            for (double latitude : List.of(6.5, 37.5)) {
                for (double longitude : List.of(68.0, 97.5)) {
                    execute(f.db(), "UPDATE properties SET latitude=?,longitude=? WHERE id=?", latitude, longitude, f.entire());
                    assertThat(value(f.db(), "SELECT ST_X(location::geometry)=longitude::float8 AND ST_Y(location::geometry)=latitude::float8 FROM properties WHERE id=?", f.entire())).isEqualTo(true);
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void protectsIdentityContactAndAggregateShapes(int target) throws Exception {
        fixture(target, f -> {
            assertThat(value(f.db(), "SELECT count(*) FROM users WHERE phone_number IS NULL AND phone_verified_at IS NULL AND role='USER' AND review_count=0 AND broker_report_count=0 AND average_rating IS NULL")).isEqualTo(3L);
            for (String change : List.of("phone_verified_at=statement_timestamp()", "phone_number='invalid'", "review_count=-1", "broker_report_count=1", "average_rating=5", "review_count=1", "role='OWNER'", "full_name=' '")) {
                reject(f.db(), "23514", "UPDATE users SET " + change + " WHERE id=?", f.owner());
            }
            String phone = "+91" + "0".repeat(10);
            reject(f.db(), "23514", "UPDATE users SET phone_number=?,phone_verified_at=statement_timestamp() WHERE id=?", "invalid", f.owner());
            reject(f.db(), "23514", "UPDATE users SET phone_number=? WHERE id=?", phone, f.owner());
            execute(f.db(), "UPDATE users SET phone_number=?,phone_verified_at=statement_timestamp() WHERE id=?", phone, f.owner());
            reject(f.db(), "23505", "UPDATE users SET phone_number=?,phone_verified_at=statement_timestamp() WHERE id=?", phone, f.tenant());
            execute(f.db(), "UPDATE users SET review_count=1,average_rating=3 WHERE id=?", f.owner());
            reject(f.db(), "23514", "UPDATE users SET broker_report_count=2 WHERE id=?", f.owner());
            reject(f.db(), "23514", "UPDATE users SET average_rating=6 WHERE id=?", f.owner());
            insert(f.db(), "auth_identities", row("id", UUID.randomUUID(), "user_id", f.owner(), "issuer", "https://accounts.google.com", "subject", "subject-one", "email", "same@example.invalid"));
            insert(f.db(), "auth_identities", row("id", UUID.randomUUID(), "user_id", f.tenant(), "issuer", "https://accounts.google.com", "subject", "SUBJECT-ONE", "email", "same@example.invalid"));
            reject(f.db(), "23505", "INSERT INTO auth_identities(id,user_id,issuer,subject) VALUES (?,?,?,?)", UUID.randomUUID(), f.tenant(), "https://accounts.google.com", "subject-one");
            reject(f.db(), "23514", "UPDATE auth_identities SET email=NULL,email_verified=true");
            reject(f.db(), "23503", "INSERT INTO auth_identities(id,user_id,issuer,subject) VALUES (?,?,?,?)", UUID.randomUUID(), UUID.randomUUID(), "https://accounts.google.com", "missing-user");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void protectsImageReservationAndFinalizationShapes(int target) throws Exception {
        fixture(target, f -> {
            UUID image = image(f, "staging/first");
            image(f, "staging/second");
            assertThat(value(f.db(), "SELECT count(*) FROM property_images WHERE object_key IS NULL AND validation_state='PENDING' AND display_order=0")).isEqualTo(2L);
            for (String change : List.of("object_key='final/first'", "validation_state='VALIDATED'", "validation_state='UNKNOWN'", "segment='UNKNOWN'", "display_order=-1", "upload_expires_at=created_at", "upload_object_key=''")) {
                reject(f.db(), "23514", "UPDATE property_images SET " + change + " WHERE id=?", image);
            }
            execute(f.db(), "UPDATE property_images SET validation_state='VALIDATED',object_key='final/first' WHERE id=?", image);
            reject(f.db(), "23514", "UPDATE property_images SET object_key=' ' WHERE id=?", image);
            reject(f.db(), "23514", "UPDATE property_images SET object_key=upload_object_key WHERE id=?", image);
            reject(f.db(), "23514", "UPDATE property_images SET validation_state='REJECTED' WHERE id=?", image);
            reject(f.db(), "23505", "UPDATE property_images SET validation_state='VALIDATED',object_key='final/first' WHERE id<>?", image);
            reject(f.db(), "23505", "UPDATE property_images SET upload_object_key='staging/first' WHERE id<>?", image);
            reject(f.db(), "23503", "UPDATE property_images SET property_id=? WHERE id=?", UUID.randomUUID(), image);
            for (String segment : List.of("ROOM", "HALL", "BATHROOM", "KITCHEN", "EXTERIOR_AMENITY")) {
                execute(f.db(), "UPDATE property_images SET segment=? WHERE id=?", segment, image);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void generatesKolkataDaysIndependentOfSessionTimezone(int target) throws Exception {
        fixture(target, f -> {
            for (String timezone : List.of("UTC", "America/Los_Angeles", "Asia/Kolkata")) {
                execute(f.db(), "SELECT set_config('TimeZone',?,true)", timezone);
                UUID first = reveal(f, f.entire(), "2026-10-21T18:29:59Z");
                UUID second = reveal(f, f.entire(), "2026-10-21T18:30:00Z");
                assertThat(value(f.db(), "SELECT revealed_date::text FROM contact_reveals WHERE id=?", first)).isEqualTo("2026-10-21");
                assertThat(value(f.db(), "SELECT revealed_date::text FROM contact_reveals WHERE id=?", second)).isEqualTo("2026-10-22");
                reject(f.db(), "23505", "INSERT INTO contact_reveals(id,tenant_id,property_id,owner_id,created_at) VALUES (?,?,?,?,?)", UUID.randomUUID(), f.tenant(), f.entire(), f.owner(), OffsetDateTime.parse("2026-10-21T18:31:00Z"));
                reject(f.db(), "428C9", "UPDATE contact_reveals SET revealed_date=DATE '2026-10-23' WHERE id=?", first);
                execute(f.db(), "DELETE FROM contact_reveals");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void enforcesRevealOwnershipAndSelfRevealChecks(int target) throws Exception {
        fixture(target, f -> {
            reject(f.db(), "23503", "INSERT INTO contact_reveals(id,tenant_id,property_id,owner_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.tenant(), f.entire(), f.otherOwner());
            reject(f.db(), "23514", "INSERT INTO contact_reveals(id,tenant_id,property_id,owner_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.owner(), f.entire(), f.owner());
            reject(f.db(), "23503", "INSERT INTO contact_reveals(id,tenant_id,property_id,owner_id) VALUES (?,?,?,?)", UUID.randomUUID(), UUID.randomUUID(), f.entire(), f.owner());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void enforcesReviewPairIdentityAndBrokerageReportShape(int target) throws Exception {
        fixture(target, f -> {
            UUID review = UUID.randomUUID();
            insert(f.db(), "owner_reviews", row("id", review, "property_id", f.entire(), "owner_id", f.owner(), "reviewer_id", f.tenant(), "rating", 4, "issue_tag", "GENUINE_OWNER"));
            for (String change : List.of("rating=0", "rating=6", "issue_tag='UNKNOWN'", "issue_tag='ASKED_BROKERAGE'", "is_auto_broker_report=true", "comment_text=' '")) {
                reject(f.db(), "23514", "UPDATE owner_reviews SET " + change + " WHERE id=?", review);
            }
            reject(f.db(), "23503", "UPDATE owner_reviews SET owner_id=? WHERE id=?", f.otherOwner(), review);
            reject(f.db(), "23514", "UPDATE owner_reviews SET reviewer_id=owner_id WHERE id=?", review);
            reject(f.db(), "23505", "INSERT INTO owner_reviews(id,property_id,owner_id,reviewer_id,rating,issue_tag,is_auto_broker_report) VALUES (?,?,?,?,1,'ASKED_BROKERAGE',true)", UUID.randomUUID(), f.entire(), f.owner(), f.tenant());
            execute(f.db(), "UPDATE owner_reviews SET rating=1,issue_tag='ASKED_BROKERAGE',is_auto_broker_report=true WHERE id=?", review);
            reject(f.db(), "23514", "UPDATE owner_reviews SET rating=2 WHERE id=?", review);
            reject(f.db(), "23514", "UPDATE owner_reviews SET is_auto_broker_report=false WHERE id=?", review);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void restrictsDeletionAndOwnerReassignmentAcrossReferencedRows(int target) throws Exception {
        fixture(target, f -> {
            image(f, "staging/restrict");
            reveal(f, f.entire(), "2026-10-21T18:30:00Z");
            reject(f.db(), "23503", "DELETE FROM properties WHERE id=?", f.entire());
            reject(f.db(), "23503", "DELETE FROM users WHERE id=?", f.owner());
            reject(f.db(), "23503", "DELETE FROM users WHERE id=?", f.tenant());
            reject(f.db(), "23503", "UPDATE properties SET owner_id=? WHERE id=?", f.otherOwner(), f.entire());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void installsAllDocumentedIndexesWithMixedSortDirections(int target) throws Exception {
        try (var connection = databases.get(target).connect()) {
            assertThat(strings(connection, "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname LIKE 'ix_%' ORDER BY indexname")).containsExactly(
                    "ix_auth_identities_user", "ix_contact_reveals_property_owner", "ix_contact_reveals_tenant_day", "ix_owner_reviews_owner", "ix_owner_reviews_property_owner", "ix_properties_city_filters", "ix_properties_city_newest", "ix_properties_city_rent_asc", "ix_properties_city_rent_desc", "ix_properties_location", "ix_properties_newest", "ix_properties_owner_status", "ix_properties_rent_asc", "ix_properties_rent_desc", "ix_property_images_property_order");
            assertThat(value(connection, "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='ix_properties_location'").toString()).contains("USING gist (location)");
            for (String prefix : List.of("ix_properties_", "ix_properties_city_")) {
                String city = prefix.contains("city_") ? "city, " : "";
                for (var sort : Map.of("newest", "created_at DESC", "rent_asc", "monthly_rent", "rent_desc", "monthly_rent DESC").entrySet()) {
                    String definition = value(connection, "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname=?", prefix + sort.getKey()).toString();
                    assertThat(definition).contains("(" + city + "status, " + sort.getValue() + ", id)").contains("WHERE", "ACTIVE", "FULL_OCCUPIED");
                }
            }
        }
    }

    private Database database(String mode, String name, String user, String password) {
        String external = System.getenv("OPENNEST_SCHEMA_TEST_" + mode + "_URL");
        if (external != null) {
            URI uri = URI.create(external.substring("jdbc:".length()));
            if (!external.startsWith("jdbc:postgresql://") || !uri.getPath().endsWith("_schema_test")
                    || uri.getQuery() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("External schema tests require a dedicated PostgreSQL database ending in _schema_test");
            }
            String externalUser = requiredEnvironment("OPENNEST_SCHEMA_TEST_" + mode + "_USER");
            String externalPassword = requiredEnvironment("OPENNEST_SCHEMA_TEST_" + mode + "_PASSWORD");
            return new Database(mode, uri.getHost(), uri.getPort(), uri.getPath().substring(1), externalUser, externalPassword);
        }
        var container = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername(user).withPassword(password);
        containers.add(container);
        container.start();
        return new Database(mode, container.getHost(), container.getMappedPort(5432), name, user, password);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing external schema test credential");
        }
        return value;
    }

    private void fixture(int target, FixtureCase test) throws Exception {
        try (var connection = databases.get(target).connect()) {
            connection.setAutoCommit(false);
            try {
                UUID owner = user(connection);
                UUID tenant = user(connection);
                UUID otherOwner = user(connection);
                UUID entire = property(connection, owner, "FLAT", "ENTIRE_FLAT", "BENGALURU");
                UUID room = property(connection, owner, "FLAT", "ROOM", "PUNE");
                UUID pg = property(connection, owner, "PG_HOSTEL", null, "HYDERABAD");
                test.run(new Fixture(connection, owner, tenant, otherOwner, entire, room, pg));
            } finally {
                connection.rollback();
            }
        }
    }

    private UUID user(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        insert(connection, "users", row("id", id, "full_name", "Fabricated schema-test account"));
        return id;
    }

    private UUID property(Connection connection, UUID owner, String category, String mode, String city) throws SQLException {
        UUID id = UUID.randomUUID();
        var values = row("id", id, "owner_id", owner, "title", "Fabricated schema-test listing", "description", "Test-only listing", "category", category, "flat_rental_mode", mode, "city", city, "locality", "FABRICATED LOCALITY", "private_address", "Fabricated test address", "latitude", 12.9716, "longitude", 77.5946, "monthly_rent", 10000, "security_deposit", 0);
        if (category.equals("FLAT")) {
            values.put("bedrooms", 0);
        }
        if ("ENTIRE_FLAT".equals(mode)) {
            values.put("whole_flat_available", true);
            values.put("max_occupants", 4);
        } else {
            values.put("male_rooms_available", 1);
            values.put("female_rooms_available", 0);
            values.put("shared_rooms_available", 1);
            values.put("max_sharing_per_room", 2);
        }
        insert(connection, "properties", values);
        return id;
    }

    private UUID image(Fixture f, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        insert(f.db(), "property_images", row("id", id, "property_id", f.entire(), "upload_object_key", key, "segment", "ROOM", "upload_expires_at", OffsetDateTime.now().plusHours(1)));
        return id;
    }

    private UUID reveal(Fixture f, UUID property, String timestamp) throws SQLException {
        UUID id = UUID.randomUUID();
        insert(f.db(), "contact_reveals", row("id", id, "tenant_id", f.tenant(), "property_id", property, "owner_id", f.owner(), "created_at", OffsetDateTime.parse(timestamp)));
        return id;
    }

    private Map<String, Object> row(Object... values) {
        var row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private void insert(Connection connection, String table, Map<String, Object> values) throws SQLException {
        String placeholders = String.join(",", values.keySet().stream().map(key -> "?").toList());
        execute(connection, "INSERT INTO " + table + "(" + String.join(",", values.keySet()) + ") VALUES (" + placeholders + ")", values.values().toArray());
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }

    private Object value(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1);
            }
        }
    }

    private List<String> strings(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private void reject(Connection connection, String state, String sql, Object... parameters) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(connection, sql, parameters)).isInstanceOfSatisfying(SQLException.class,
                    exception -> assertThat(exception.getSQLState()).as(sql).isEqualTo(state));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private record Database(String mode, String host, int port, String name, String user, String password) {
        Connection connect() throws SQLException {
            return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + name, user, password);
        }

        @Override
        public String toString() {
            return "Schema test database [credentials redacted]";
        }
    }

    private record Fixture(Connection db, UUID owner, UUID tenant, UUID otherOwner, UUID entire, UUID room, UUID pg) {
    }

    @FunctionalInterface
    private interface FixtureCase {
        void run(Fixture fixture) throws Exception;
    }
}

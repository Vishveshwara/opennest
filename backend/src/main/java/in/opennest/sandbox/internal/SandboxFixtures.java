package in.opennest.sandbox.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

final class SandboxFixtures {
    static final int VERSION = 1;
    static final List<String> SEGMENTS = List.of("ROOM", "HALL", "BATHROOM", "KITCHEN", "EXTERIOR_AMENITY");
    record Persona(String key, String displayName, UUID userId) {}
    static final List<Persona> PERSONAS = List.of(
            persona("owner-standard", "Demo owner with no reviews"), persona("owner-top", "Demo top-rated owner"),
            persona("owner-low", "Demo owner with low ratings"), persona("owner-reported", "Demo owner with brokerage allegations"),
            persona("owner-critical", "Demo owner with allegations and low ratings"),
            persona("seeker-one", "Demo seeker with existing reviews"), persona("seeker-two", "Demo second seeker"),
            persona("seeker-three", "Demo third seeker"), persona("seeker-new", "Demo seeker ready to review"));
    private final JdbcTemplate jdbc;
    SandboxFixtures(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static UUID id(String value) { return UUID.nameUUIDFromBytes(("opennest:sandbox:v1:" + value).getBytes(StandardCharsets.UTF_8)); }
    private static Persona persona(String key, String name) { return new Persona(key, name, id(key)); }

    void seed(Instant now) {
        PERSONAS.forEach(persona -> jdbc.update("insert into users (id, full_name) values (?,?)", persona.userId(), persona.displayName()));
        var cities = List.of("DELHI", "BENGALURU", "MUMBAI", "PUNE", "HYDERABAD");
        var latitudes = List.of(28.61, 12.97, 19.08, 18.52, 17.39);
        var longitudes = List.of(77.21, 77.59, 72.88, 73.86, 78.49);
        for (int owner = 0; owner < 5; owner++) {
            int count = owner == 4 ? 5 : 3;
            for (int mode = 0; mode < count; mode++) {
                boolean entire = mode == 0 || mode >= 3;
                boolean pg = mode == 2;
                String status = owner == 0 && mode == 1 ? "PAUSED" : owner == 0 && pg ? "FULL_OCCUPIED" : "ACTIVE";
                UUID property = id("listing-" + owner + "-" + mode);
                UUID ownerId = PERSONAS.get(owner).userId();
                jdbc.update("insert into properties (id, owner_id, title, description, category, flat_rental_mode, status, city, locality, private_address, latitude, longitude, monthly_rent, security_deposit, bedrooms, has_wifi, whole_flat_available, max_occupants, male_rooms_available, female_rooms_available, shared_rooms_available, max_sharing_per_room, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        property, ownerId, "OpenNest fabricated " + (pg ? "PG" : entire ? "entire flat" : "room") + " in " + cities.get(owner),
                        "Fabricated recruiter demonstration. Images are illustrative fixtures. No accommodation or callable contact is offered.",
                        pg ? "PG_HOSTEL" : "FLAT", pg ? null : entire ? "ENTIRE_FLAT" : "ROOM", status, cities.get(owner),
                        "DEMO LOCALITY", "Fabricated demonstration address", latitudes.get(owner), longitudes.get(owner),
                        entire ? 25000 : pg ? 6500 : 8000, entire ? 50000 : 10000, pg ? null : entire ? 2 : 3, true,
                        entire ? true : null, entire ? 4 : null, entire ? null : status.equals("FULL_OCCUPIED") ? 0 : 1,
                        entire ? null : 0, entire ? null : status.equals("FULL_OCCUPIED") ? 0 : 1,
                        entire ? null : owner == 4 ? 10 : 2, Timestamp.from(now.minus(mode, ChronoUnit.HOURS)), Timestamp.from(now));
                var segments = pg ? List.of("ROOM", "BATHROOM") : List.of("ROOM", "HALL", "BATHROOM", "KITCHEN");
                int displayOrder = 0;
                for (String segment : segments) {
                    int bytes;
                    try { bytes = new ClassPathResource("sandbox/images/" + segment + ".png").getContentAsByteArray().length; }
                    catch (java.io.IOException failure) { throw new IllegalStateException("OpenNest sandbox fixture is unavailable"); }
                    jdbc.update("insert into property_images (id, property_id, upload_object_key, object_key, segment, display_order, upload_expires_at, validation_state, declared_content_type, declared_byte_size, content_type, byte_size, width, height) values (?,?,?,?,?,?,current_timestamp + interval '10 minutes','VALIDATED','image/png',?,'image/png',?,320,240)",
                            id("image-" + owner + "-" + mode + "-" + segment), property, "sandbox-staging/" + property + "/" + segment,
                            "sandbox-fixtures/" + id("image-" + owner + "-" + mode + "-" + segment) + "/" + segment + ".png", segment, displayOrder++, bytes, bytes);
                }
                for (int seeker = 5; seeker < 9; seeker++) {
                    int age = 2 + (owner * 3 + mode) / 8;
                    jdbc.update("insert into contact_reveals (id, tenant_id, property_id, owner_id, created_at) values (?,?,?,?,?)",
                            id("reveal-" + owner + "-" + mode + "-" + seeker), PERSONAS.get(seeker).userId(), property, ownerId, Timestamp.from(now.minus(age, ChronoUnit.DAYS)));
                }
            }
        }
        review(1, 5, 5, false); review(1, 6, 5, false); review(1, 7, 5, false);
        review(2, 5, 2, false); review(2, 6, 2, false);
        review(3, 5, 1, true); review(3, 6, 5, false); review(3, 7, 5, false);
        review(4, 5, 1, true); review(4, 6, 2, false);
        jdbc.update("update users u set average_rating = r.average, review_count = r.reviews, broker_report_count = r.reports from (select owner_id, avg(rating) average, count(*) reviews, count(*) filter (where is_auto_broker_report) reports from owner_reviews group by owner_id) r where u.id = r.owner_id");
    }

    private void review(int owner, int seeker, int rating, boolean report) {
        jdbc.update("insert into owner_reviews (id, property_id, owner_id, reviewer_id, rating, issue_tag, comment_text, is_auto_broker_report) values (?,?,?,?,?,?,?,?)",
                id("review-" + owner + "-" + seeker), id("listing-" + owner + "-0"), PERSONAS.get(owner).userId(),
                PERSONAS.get(seeker).userId(), rating, report ? "ASKED_BROKERAGE" : "GENUINE_OWNER",
                report ? "Fabricated brokerage allegation for the recruiter demonstration." : "Fabricated review for the recruiter demonstration.", report);
    }
}

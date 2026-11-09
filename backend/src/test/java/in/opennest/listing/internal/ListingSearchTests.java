package in.opennest.listing.internal;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Stream;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingSearchTests {
    private final JsonMapper json = new JsonMapper();

    @Test void defaultsToNationwideActiveResults() {
        var search = ListingSearch.parse(query(), false);
        assertThat(search.parameters()).containsOnlyKeys("status").containsEntry("status", "ACTIVE");
        assertThat(search.where()).doesNotContain("city");
        assertThat(search.page()).isZero();
        assertThat(search.size()).isEqualTo(20);
        assertThat(search.sort()).isEqualTo("NEWEST");
    }

    @ParameterizedTest @MethodSource("sorts")
    void acceptsOnlyDocumentedSortsAndAddsStableUuidTies(String sort, String expression) {
        var input = query("sort", sort);
        if (sort.equals("DISTANCE_ASC")) input.addAll(query("lat", "12.9", "lng", "77.6", "radiusKm", "2.5"));
        var search = ListingSearch.parse(input, false);
        assertThat(search.sort()).isEqualTo(sort);
        assertThat(search.order()).startsWith(expression).endsWith(", p.id asc");
    }
    static Stream<Arguments> sorts() {
        return Stream.of(Arguments.of("NEWEST", "p.created_at desc"), Arguments.of("RENT_ASC", "p.monthly_rent asc"),
                Arguments.of("RENT_DESC", "p.monthly_rent desc"), Arguments.of("ROOMS_DESC", "p.total_rooms_available desc nulls last"),
                Arguments.of("RATING_DESC", "o.average_rating desc nulls last"), Arguments.of("DISTANCE_ASC", "ST_Distance"));
    }

    @Test void normalizesCityAndLocalityIdenticallyWithoutIntroducingAliases() {
        var search = ListingSearch.parse(query("city", "  Ｐｕｎｅ  ", "locality", "  New\u00a0  Town "), false);
        assertThat(search.parameters()).containsEntry("city", "PUNE").containsEntry("locality", "NEW TOWN");
        assertThat(ListingInput.normalized("Bengaluru")).isNotEqualTo(ListingInput.normalized("Bangalore"));
    }

    @Test void composesQuotedRentBedroomRoomAndAmenityFilters() {
        var search = ListingSearch.parse(query("category", "FLAT", "flatRentalMode", "ROOM", "minRent", "0", "maxRent", "9000",
                "minBedrooms", "3", "maxBedrooms", "3", "minTotalRooms", "1", "maxTotalRooms", "2", "genderRoomAvailable", "FEMALE",
                "maxSharingPerRoom", "2", "hasWifi", "true", "hasGym", "false"), false);
        assertThat(search.parameters()).containsEntry("minRent", 0L).containsEntry("maxRent", 9000L)
                .containsEntry("minBedrooms", 3L).containsEntry("minTotalRooms", 1L).containsEntry("hasWifi", true).containsEntry("hasGym", false);
        assertThat(search.where()).contains("p.category = 'FLAT'", "p.total_rooms_available is not null", "p.female_rooms_available > 0");
        assertThat(search.parameters()).doesNotContainKey("hasSwimmingPool");
    }

    @ParameterizedTest @ValueSource(strings = {"MALE", "FEMALE", "SHARED"})
    void genderVacancyFiltersRequireRoomInventory(String gender) {
        var search = ListingSearch.parse(query("genderRoomAvailable", gender), false);
        assertThat(search.where()).contains("p.total_rooms_available is not null", "p." + gender.toLowerCase() + "_rooms_available > 0");
    }

    @Test void convertsRadiusToMetresAndAllowsCoarseCoordinateEdges() {
        var search = ListingSearch.parse(query("lat", "6.5", "lng", "97.5", "radiusKm", "100", "sort", "DISTANCE_ASC"), false);
        assertThat(search.parameters().get("radius")).isEqualTo(new BigDecimal("100000"));
        assertThat(search.where()).contains("ST_DWithin");
        assertThat(ListingSearch.parse(query("lat", "37.5", "lng", "68", "radiusKm", "0.001"), false).parameters().get("radius"))
                .isEqualTo(new BigDecimal("1.000"));
    }

    @Test void acceptsMaximumOffsetAndLargeRoomTotalsWithoutIntegerOverflow() {
        var search = ListingSearch.parse(query("page", "200", "size", "50", "minTotalRooms", "6442450941"), false);
        assertThat(search.page()).isEqualTo(200);
        assertThat(search.size()).isEqualTo(50);
        assertThat(search.parameters()).containsEntry("minTotalRooms", 6442450941L);
    }

    @Test void ownerCollectionIncludesPausedByDefaultAndAllowsOnlyItsDocumentedQuery() {
        var defaults = ListingSearch.parse(query(), true);
        assertThat(defaults.where()).isEmpty();
        assertThat(defaults.parameters()).isEmpty();
        assertThat(ListingSearch.parse(query("status", "PAUSED", "page", "1", "size", "10"), true).parameters())
                .containsEntry("status", "PAUSED");
        invalid(() -> ListingSearch.parse(query("city", "Pune"), true));
        invalid(() -> ListingSearch.parse(query("sort", "NEWEST"), true));
    }

    @ParameterizedTest @MethodSource("invalidQueries")
    void rejectsInvalidOrIncompatibleQueries(String[] pairs) { invalid(() -> ListingSearch.parse(query(pairs), false)); }
    static Stream<Arguments> invalidQueries() {
        return Stream.of(new String[]{"unknown", "x"}, new String[]{"city", ""}, new String[]{"city", " "},
                new String[]{"status", "PAUSED"}, new String[]{"status", "active"}, new String[]{"sort", "monthly_rent desc"},
                new String[]{"sort", "DISTANCE_ASC"}, new String[]{"size", "0"}, new String[]{"size", "51"},
                new String[]{"page", "-1"}, new String[]{"page", "1.5"}, new String[]{"page", "2147483648"},
                new String[]{"page", "201", "size", "50"}, new String[]{"minRent", "5", "maxRent", "4"},
                new String[]{"minBedrooms", "3", "maxBedrooms", "2"}, new String[]{"minTotalRooms", "2", "maxTotalRooms", "1"},
                new String[]{"minRent", "2147483648"}, new String[]{"minRent", "1.5"}, new String[]{"minRent", "-1"},
                new String[]{"minTotalRooms", "9223372036854775808"}, new String[]{"maxSharingPerRoom", "0"},
                new String[]{"maxSharingPerRoom", "11"}, new String[]{"hasWifi", "TRUE"}, new String[]{"genderRoomAvailable", "OTHER"},
                new String[]{"category", "PG_HOSTEL", "flatRentalMode", "ROOM"}, new String[]{"category", "PG_HOSTEL", "minBedrooms", "0"},
                new String[]{"flatRentalMode", "ENTIRE_FLAT", "minTotalRooms", "0"},
                new String[]{"flatRentalMode", "ENTIRE_FLAT", "genderRoomAvailable", "SHARED"},
                new String[]{"flatRentalMode", "ENTIRE_FLAT", "maxSharingPerRoom", "2"}, new String[]{"lat", "12"},
                new String[]{"lat", "6.49", "lng", "77", "radiusKm", "1"}, new String[]{"lat", "37.51", "lng", "77", "radiusKm", "1"},
                new String[]{"lat", "12", "lng", "67.99", "radiusKm", "1"}, new String[]{"lat", "12", "lng", "97.51", "radiusKm", "1"},
                new String[]{"lat", "12", "lng", "77", "radiusKm", "0"}, new String[]{"lat", "12", "lng", "77", "radiusKm", "100.1"},
                new String[]{"lat", "NaN", "lng", "77", "radiusKm", "1"}).map(values -> Arguments.of((Object) values));
    }

    @Test void rejectsDuplicateScalarParametersEvenWhenTheirValuesMatch() {
        var input = query("city", "Pune", "city", "Pune");
        invalid(() -> ListingSearch.parse(input, false));
    }

    @ParameterizedTest @ValueSource(strings = {"\u00a0", "\u0000", "\ufdfa\ufdfa\ufdfa\ufdfa\ufdfa\ufdfa\ufdfa"})
    void rejectsNormalizedEmptyNullByteOrOverlongLocations(String value) {
        invalid(() -> ListingSearch.parse(query("city", value), false));
        invalid(() -> ListingSearch.parse(query("locality", value), false));
    }

    @Test void acceptsAllThreeRentalModesWithIndependentCapacityAndLayout() {
        var entire = ListingInput.draft(draft("FLAT", "ENTIRE_FLAT", "\"wholeFlatAvailable\":true,\"maxOccupants\":25", "\"bedrooms\":0,"));
        assertThat(entire.inventory()).isEqualTo(new ListingInput.EntireFlat(true, 25));
        assertThat(entire.bedrooms()).isZero();
        var room = ListingInput.draft(draft("FLAT", "ROOM", "\"maleRoomsAvailable\":1,\"femaleRoomsAvailable\":0,\"sharedRoomsAvailable\":2,\"maxSharingPerRoom\":2", "\"bedrooms\":3,"));
        assertThat(room.inventory()).isEqualTo(new ListingInput.Rooms(1, 0, 2, 2));
        assertThat(room.bedrooms()).isEqualTo(3);
        var pg = ListingInput.draft(draft("PG_HOSTEL", null, "\"maleRoomsAvailable\":0,\"femaleRoomsAvailable\":0,\"sharedRoomsAvailable\":0,\"maxSharingPerRoom\":10", ""));
        assertThat(pg.mode()).isNull();
        assertThat(pg.bedrooms()).isNull();
        assertThat(pg.inventory().publishedStatus()).isEqualTo(ListingInput.Status.FULL_OCCUPIED);
    }

    @Test void rejectsIncorrectModesUnknownFieldsAndNonIntegralInventory() {
        invalid(() -> ListingInput.draft(draft("FLAT", null, "\"wholeFlatAvailable\":true,\"maxOccupants\":2", "\"bedrooms\":1,")));
        invalid(() -> ListingInput.draft(draft("PG_HOSTEL", "ROOM", "\"maleRoomsAvailable\":1,\"femaleRoomsAvailable\":0,\"sharedRoomsAvailable\":0,\"maxSharingPerRoom\":2", "")));
        invalid(() -> ListingInput.fields(json.readTree("{\"status\":\"ACTIVE\"}"), Set.of("version")));
        invalid(() -> ListingInput.inventory(json.readTree("{\"wholeFlatAvailable\":true,\"maxOccupants\":2.0}"), true));
        invalid(() -> ListingInput.inventory(json.readTree("{\"wholeFlatAvailable\":true,\"maxOccupants\":0}"), true));
        invalid(() -> ListingInput.inventory(json.readTree("{\"maleRoomsAvailable\":1,\"femaleRoomsAvailable\":0,\"sharedRoomsAvailable\":0,\"maxSharingPerRoom\":11}"), false));
        invalid(() -> ListingInput.inventory(json.readTree("{\"wholeFlatAvailable\":true,\"maxOccupants\":2,\"maleRoomsAvailable\":null}"), true));
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.0", "\"1\"", "null", "9223372036854775808"})
    void rejectsInvalidVersions(String value) { invalid(() -> ListingInput.version(json.readTree("{\"version\":" + value + "}"))); }

    @Test void roomTotalsUseLongArithmeticAndCapacityDoesNotCreateVacancy() {
        assertThat(new ListingInput.Rooms(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 2).publishedStatus()).isEqualTo(ListingInput.Status.ACTIVE);
        assertThat(new ListingInput.EntireFlat(false, 100).publishedStatus()).isEqualTo(ListingInput.Status.FULL_OCCUPIED);
        assertThat(new ListingInput.Rooms(0, 0, 0, 10).publishedStatus()).isEqualTo(ListingInput.Status.FULL_OCCUPIED);
    }

    private JsonNode draft(String category, String mode, String inventory, String bedrooms) {
        return json.readTree("{\"title\":\"Test accommodation\",\"description\":\"Fabricated listing\",\"category\":\"" + category + "\","
                + (mode == null ? "" : "\"flatRentalMode\":\"" + mode + "\",") + bedrooms
                + "\"city\":\"Pune\",\"locality\":\"Test locality\",\"privateAddress\":\"Fabricated address\",\"latitude\":18.52,\"longitude\":73.85,"
                + "\"monthlyRent\":8000,\"securityDeposit\":0,\"hasWifi\":true,\"hasGym\":false,\"hasSwimmingPool\":false," + inventory + "}");
    }
    private static LinkedMultiValueMap<String, String> query(String... pairs) {
        var input = new LinkedMultiValueMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) input.add(pairs[index], pairs[index + 1]);
        return input;
    }
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RequestFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.INVALID_INPUT));
    }
}

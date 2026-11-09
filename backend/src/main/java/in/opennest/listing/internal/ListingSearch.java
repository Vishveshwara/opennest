package in.opennest.listing.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

record ListingSearch(String where, Map<String, Object> parameters, String order, String sort, int page, int size) {
    private static final Set<String> KEYS = Set.of("city", "locality", "category", "flatRentalMode", "status", "minRent", "maxRent",
            "minBedrooms", "maxBedrooms", "minTotalRooms", "maxTotalRooms", "genderRoomAvailable", "maxSharingPerRoom",
            "hasWifi", "hasGym", "hasSwimmingPool", "lat", "lng", "radiusKm", "page", "size", "sort");

    static ListingSearch parse(MultiValueMap<String, String> input, boolean mine) {
        input.forEach((key, values) -> ListingInput.require((mine ? Set.of("page", "size", "status") : KEYS).contains(key)
                && values.size() == 1 && values.getFirst() != null && !values.getFirst().isBlank()));
        int page = number(input, "page", 0, Integer.MAX_VALUE, 0);
        int size = number(input, "size", 1, 50, 20);
        ListingInput.require((long) page * size <= 10000);
        var clauses = new ArrayList<String>();
        var parameters = new LinkedHashMap<String, Object>();
        String status = input.getFirst("status");
        if (!mine || status != null) equal(clauses, parameters, "status", "p.status",
                enumValue(status == null ? "ACTIVE" : status, mine ? Set.of("ACTIVE", "FULL_OCCUPIED", "PAUSED") : Set.of("ACTIVE", "FULL_OCCUPIED")));
        if (mine) return new ListingSearch(String.join(" and ", clauses), parameters, "p.created_at desc, p.id asc", "NEWEST", page, size);
        for (String name : List.of("city", "locality")) {
            String value = input.getFirst(name);
            if (value != null) {
                ListingInput.require(value.length() <= 120);
                equal(clauses, parameters, name, "p." + name, ListingInput.normalized(value));
            }
        }
        String category = input.getFirst("category"), mode = input.getFirst("flatRentalMode");
        if (category != null) equal(clauses, parameters, "category", "p.category", enumValue(category, Set.of("FLAT", "PG_HOSTEL")));
        if (mode != null) {
            ListingInput.require(!"PG_HOSTEL".equals(category));
            equal(clauses, parameters, "mode", "p.flat_rental_mode", enumValue(mode, Set.of("ENTIRE_FLAT", "ROOM")));
        }
        range(input, clauses, parameters, "Rent", "p.monthly_rent", Integer.MAX_VALUE);
        boolean bedrooms = input.containsKey("minBedrooms") || input.containsKey("maxBedrooms");
        if (bedrooms) {
            ListingInput.require(!"PG_HOSTEL".equals(category));
            clauses.add("p.category = 'FLAT'");
        }
        range(input, clauses, parameters, "Bedrooms", "p.bedrooms", Integer.MAX_VALUE);
        boolean rooms = input.containsKey("minTotalRooms") || input.containsKey("maxTotalRooms")
                || input.containsKey("genderRoomAvailable") || input.containsKey("maxSharingPerRoom");
        if (rooms) {
            ListingInput.require(!"ENTIRE_FLAT".equals(mode));
            clauses.add("p.total_rooms_available is not null");
        }
        range(input, clauses, parameters, "TotalRooms", "p.total_rooms_available", Long.MAX_VALUE);
        if (input.containsKey("maxSharingPerRoom")) {
            parameters.put("sharing", number(input, "maxSharingPerRoom", 1, 10, 1));
            clauses.add("p.max_sharing_per_room <= :sharing");
        }
        if (input.containsKey("genderRoomAvailable")) {
            String column = switch (enumValue(input.getFirst("genderRoomAvailable"), Set.of("MALE", "FEMALE", "SHARED"))) {
                case "MALE" -> "male_rooms_available";
                case "FEMALE" -> "female_rooms_available";
                default -> "shared_rooms_available";
            };
            clauses.add("p." + column + " > 0");
        }
        for (var amenity : Map.of("hasWifi", "has_wifi", "hasGym", "has_gym", "hasSwimmingPool", "has_swimming_pool").entrySet()) {
            if (input.containsKey(amenity.getKey())) {
                String value = enumValue(input.getFirst(amenity.getKey()), Set.of("true", "false"));
                equal(clauses, parameters, amenity.getKey(), "p." + amenity.getValue(), Boolean.valueOf(value));
            }
        }
        boolean geo = input.containsKey("lat") || input.containsKey("lng") || input.containsKey("radiusKm");
        String point = "ST_SetSRID(ST_MakePoint(:lng, :lat),4326)::geography";
        if (geo) {
            parameters.put("lat", decimal(input, "lat", "6.5", "37.5", false));
            parameters.put("lng", decimal(input, "lng", "68", "97.5", false));
            parameters.put("radius", decimal(input, "radiusKm", "0", "100", true).multiply(BigDecimal.valueOf(1000)));
            clauses.add("ST_DWithin(p.location, " + point + ", :radius)");
        }
        String sort = input.getFirst("sort") == null ? "NEWEST" : input.getFirst("sort");
        String order = switch (sort) {
            case "NEWEST" -> "p.created_at desc";
            case "RENT_ASC" -> "p.monthly_rent asc";
            case "RENT_DESC" -> "p.monthly_rent desc";
            case "ROOMS_DESC" -> "p.total_rooms_available desc nulls last";
            case "RATING_DESC" -> "o.average_rating desc nulls last";
            case "DISTANCE_ASC" -> { ListingInput.require(geo); yield "ST_Distance(p.location, " + point + ") asc"; }
            default -> throw ListingInput.invalid();
        };
        return new ListingSearch(String.join(" and ", clauses), parameters, order + ", p.id asc", sort, page, size);
    }

    private static void range(MultiValueMap<String, String> input, List<String> clauses, Map<String, Object> parameters, String suffix, String column, long max) {
        Long minimum = null, maximum = null;
        for (String prefix : List.of("min", "max")) {
            String key = prefix + suffix;
            if (input.containsKey(key)) {
                long value;
                try { ListingInput.require(input.getFirst(key).matches("[0-9]+")); value = Long.parseLong(input.getFirst(key)); }
                catch (NumberFormatException failure) { throw ListingInput.invalid(); }
                ListingInput.require(value <= max);
                parameters.put(key, value);
                clauses.add(column + (prefix.equals("min") ? " >= :" : " <= :") + key);
                if (prefix.equals("min")) minimum = value; else maximum = value;
            }
        }
        ListingInput.require(minimum == null || maximum == null || minimum <= maximum);
    }
    static int number(MultiValueMap<String, String> input, String key, int min, int max, int fallback) {
        if (!input.containsKey(key)) return fallback;
        try {
            ListingInput.require(input.getFirst(key).matches("[0-9]+"));
            int result = Integer.parseInt(input.getFirst(key));
            ListingInput.require(result >= min && result <= max);
            return result;
        } catch (NumberFormatException failure) { throw ListingInput.invalid(); }
    }
    private static BigDecimal decimal(MultiValueMap<String, String> input, String name, String min, String max, boolean exclusive) {
        try {
            var result = new BigDecimal(input.getFirst(name));
            ListingInput.require(result.scale() <= 8 && result.compareTo(new BigDecimal(min)) >= (exclusive ? 1 : 0) && result.compareTo(new BigDecimal(max)) <= 0);
            return result;
        } catch (NumberFormatException | NullPointerException failure) { throw ListingInput.invalid(); }
    }
    private static String enumValue(String value, Set<String> choices) { ListingInput.require(choices.contains(value)); return value; }
    private static void equal(List<String> clauses, Map<String, Object> parameters, String key, String column, Object value) {
        clauses.add(column + " = :" + key); parameters.put(key, value);
    }
}

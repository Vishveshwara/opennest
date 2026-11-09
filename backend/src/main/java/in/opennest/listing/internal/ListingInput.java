package in.opennest.listing.internal;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import in.opennest.common.RequestFailure;
import tools.jackson.databind.JsonNode;

final class ListingInput {
    enum Category { FLAT, PG_HOSTEL }
    enum FlatMode { ENTIRE_FLAT, ROOM }
    enum Status { ACTIVE, FULL_OCCUPIED, PAUSED }
    enum Segment { ROOM, HALL, BATHROOM, KITCHEN, EXTERIOR_AMENITY }

    sealed interface Inventory permits EntireFlat, Rooms {
        Status publishedStatus();
    }
    record EntireFlat(boolean available, int capacity) implements Inventory {
        public Status publishedStatus() { return available ? Status.ACTIVE : Status.FULL_OCCUPIED; }
    }
    record Rooms(int male, int female, int shared, int sharing) implements Inventory {
        public Status publishedStatus() { return (long) male + female + shared > 0 ? Status.ACTIVE : Status.FULL_OCCUPIED; }
    }
    record Draft(String title, String description, Category category, FlatMode mode, String city, String locality,
                 String address, BigDecimal latitude, BigDecimal longitude, int rent, int deposit, Integer bedrooms,
                 boolean wifi, boolean gym, boolean pool, Inventory inventory) {
        @Override public String toString() { return "Draft[private]"; }
    }

    static Draft draft(JsonNode body) {
        fields(body, Set.of("title", "description", "category", "flatRentalMode", "city", "locality", "privateAddress",
                "latitude", "longitude", "monthlyRent", "securityDeposit", "bedrooms", "hasWifi", "hasGym", "hasSwimmingPool",
                "wholeFlatAvailable", "maxOccupants", "maleRoomsAvailable", "femaleRoomsAvailable", "sharedRoomsAvailable", "maxSharingPerRoom"));
        var category = enumeration(body, "category", Category.class);
        var mode = present(body, "flatRentalMode") ? enumeration(body, "flatRentalMode", FlatMode.class) : null;
        require(category == Category.FLAT ? mode != null : mode == null);
        Integer bedrooms = category == Category.FLAT ? integer(body, "bedrooms", 0, Integer.MAX_VALUE) : null;
        if (category == Category.PG_HOSTEL) require(!present(body, "bedrooms"));
        return new Draft(text(body, "title", 160), text(body, "description", 5000), category, mode,
                normalized(text(body, "city", 120)), normalized(text(body, "locality", 120)), text(body, "privateAddress", 500),
                coordinate(body, "latitude", "6.5", "37.5"), coordinate(body, "longitude", "68", "97.5"),
                integer(body, "monthlyRent", 1, Integer.MAX_VALUE), integer(body, "securityDeposit", 0, Integer.MAX_VALUE),
                bedrooms, bool(body, "hasWifi"), bool(body, "hasGym"), bool(body, "hasSwimmingPool"), inventory(body, mode == FlatMode.ENTIRE_FLAT));
    }

    static Inventory inventory(JsonNode body, boolean entire) {
        if (entire) {
            for (String key : Set.of("maleRoomsAvailable", "femaleRoomsAvailable", "sharedRoomsAvailable", "maxSharingPerRoom"))
                require(!body.has(key));
            return new EntireFlat(bool(body, "wholeFlatAvailable"), integer(body, "maxOccupants", 1, Integer.MAX_VALUE));
        }
        require(!body.has("wholeFlatAvailable") && !body.has("maxOccupants"));
        return new Rooms(integer(body, "maleRoomsAvailable", 0, Integer.MAX_VALUE), integer(body, "femaleRoomsAvailable", 0, Integer.MAX_VALUE),
                integer(body, "sharedRoomsAvailable", 0, Integer.MAX_VALUE), integer(body, "maxSharingPerRoom", 1, 10));
    }

    static void fields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject());
        for (String name : node.propertyNames()) require(allowed.contains(name));
    }
    static long version(JsonNode node) {
        var value = node.get("version");
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0);
        return value.asLong();
    }
    static String text(JsonNode body, String name, int max) {
        var value = body.get(name);
        require(value != null && value.isString());
        String text = value.asString().strip();
        require(!text.isBlank() && text.length() <= max && text.indexOf('\0') < 0);
        return text;
    }
    static int integer(JsonNode body, String name, int min, int max) {
        var value = body.get(name);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        int result = value.asInt();
        require(result >= min && result <= max);
        return result;
    }
    static boolean bool(JsonNode body, String name) {
        var value = body.get(name);
        require(value != null && value.isBoolean());
        return value.asBoolean();
    }
    static <E extends Enum<E>> E enumeration(JsonNode body, String name, Class<E> type) {
        try { return Enum.valueOf(type, text(body, name, 50)); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    static BigDecimal coordinate(JsonNode body, String name, String min, String max) {
        var value = body.get(name);
        require(value != null && value.isNumber());
        var number = value.decimalValue();
        require(number.scale() <= 6 && number.compareTo(new BigDecimal(min)) >= 0 && number.compareTo(new BigDecimal(max)) <= 0);
        return number;
    }
    static boolean present(JsonNode body, String name) { return body.has(name) && !body.get(name).isNull(); }
    static String normalized(String value) {
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip().toUpperCase(Locale.ROOT);
        require(!result.isBlank() && result.length() <= 120 && result.indexOf('\0') < 0);
        return result;
    }
    static void require(boolean valid) { if (!valid) throw invalid(); }
    static RequestFailure invalid() { return new RequestFailure(RequestFailure.Kind.INVALID_INPUT); }
}

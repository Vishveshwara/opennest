package in.opennest.interaction.internal;

import java.util.Set;
import in.opennest.common.RequestFailure;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

final class InteractionInput {
    enum IssueTag { GENUINE_OWNER, MAINTENANCE_ISSUE, FAKE_PHOTOS, OTHER }
    record Review(int rating, IssueTag issueTag, String commentText) {}
    record Page(int page, int size) {}

    static Review review(JsonNode body) {
        fields(body, Set.of("rating", "issueTag", "commentText"));
        var rating = body.get("rating");
        require(rating != null && rating.isIntegralNumber() && rating.canConvertToInt() && rating.intValue() >= 1 && rating.intValue() <= 5);
        var tag = body.get("issueTag");
        require(tag != null && tag.isString());
        IssueTag issue;
        try { issue = IssueTag.valueOf(tag.stringValue()); } catch (IllegalArgumentException failure) { throw invalid(); }
        var comment = body.get("commentText");
        String text = null;
        if (comment != null && !comment.isNull()) {
            require(comment.isString());
            text = comment.stringValue().strip();
            require(!text.isBlank() && text.length() <= 2000 && text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'));
        }
        return new Review(rating.intValue(), issue, text);
    }

    static void empty(JsonNode body) { if (body != null) fields(body, Set.of()); }

    static void fields(JsonNode body, Set<String> allowed) {
        require(body != null && body.isObject());
        body.propertyNames().forEach(name -> require(allowed.contains(name)));
    }

    static Page page(MultiValueMap<String, String> query) {
        query.forEach((key, values) -> require(Set.of("page", "size").contains(key) && values.size() == 1));
        int page = number(query.getFirst("page"), 0);
        int size = number(query.getFirst("size"), 20);
        require(size >= 1 && size <= 50 && (long) page * size <= 10000);
        return new Page(page, size);
    }

    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        require(value.matches("[0-9]{1,10}"));
        try { return Integer.parseInt(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    static void require(boolean valid) { if (!valid) throw invalid(); }
    static RequestFailure invalid() { return new RequestFailure(RequestFailure.Kind.INVALID_INPUT); }
}

package in.opennest.interaction.internal;

import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InteractionInputTests {
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"rating\":0,\"issueTag\":\"OTHER\"}", "{\"rating\":6,\"issueTag\":\"OTHER\"}",
            "{\"rating\":1.5,\"issueTag\":\"OTHER\"}", "{\"rating\":\"5\",\"issueTag\":\"OTHER\"}",
            "{\"rating\":5,\"issueTag\":\"ASKED_BROKERAGE\"}", "{\"rating\":5,\"issueTag\":\"OTHER\",\"ownerId\":\"spoofed\"}",
            "{\"rating\":5,\"issueTag\":\"OTHER\",\"commentText\":\"   \"}", "{\"rating\":5,\"issueTag\":\"OTHER\",\"commentText\":123}"})
    void rejectsInvalidReviewShapes(String input) {
        assertThatThrownBy(() -> InteractionInput.review(json.readTree(input))).isInstanceOf(RequestFailure.class);
    }
    @Test void supportsOptionalCommentsAndBoundsText() {
        assertThat(InteractionInput.review(json.readTree("{\"rating\":5,\"issueTag\":\"OTHER\"}")).commentText()).isNull();
        assertThat(InteractionInput.review(json.readTree("{\"rating\":5,\"issueTag\":\"OTHER\",\"commentText\":\" trimmed \"}")).commentText()).isEqualTo("trimmed");
        assertThatThrownBy(() -> InteractionInput.review(json.readTree("{\"rating\":5,\"issueTag\":\"OTHER\",\"commentText\":\"" + "x".repeat(2001) + "\"}"))).isInstanceOf(RequestFailure.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483648", "0.5", "", " 1", "10001"})
    void rejectsInvalidPages(String page) {
        var query = new LinkedMultiValueMap<String, String>(); query.add("page", page);
        assertThatThrownBy(() -> InteractionInput.page(query)).isInstanceOf(RequestFailure.class);
    }
    @Test void rejectsUnknownDuplicateAndOutOfBoundPagination() {
        var query = new LinkedMultiValueMap<String, String>(); query.add("size", "51");
        assertThatThrownBy(() -> InteractionInput.page(query)).isInstanceOf(RequestFailure.class);
        query.clear(); query.add("page", "1"); query.add("page", "2");
        assertThatThrownBy(() -> InteractionInput.page(query)).isInstanceOf(RequestFailure.class);
        query.clear(); query.add("sort", "rating");
        assertThatThrownBy(() -> InteractionInput.page(query)).isInstanceOf(RequestFailure.class);
        query.clear();
        assertThat(InteractionInput.page(query)).isEqualTo(new InteractionInput.Page(0, 20));
    }
}

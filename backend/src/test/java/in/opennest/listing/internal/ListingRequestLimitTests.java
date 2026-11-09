package in.opennest.listing.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import in.opennest.common.internal.web.ProblemResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ListingRequestLimitTests {
    private ListingRequestLimit filter() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("https://opennest.example.invalid"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/v1/**", configuration);
        return new ListingRequestLimit(new ProblemResponses(new JsonMapper()), source);
    }
    @ParameterizedTest @ValueSource(strings = {"/api/v1/listings", "/%61pi/v1/listings", "/api/v1/%6cistings"})
    void decodedContextRelativeListingPathsRejectDuplicateKeysWithCors(String path) throws Exception {
        var request = request("/context" + path, "{\"title\":\"first\",\"title\":\"second\"}");
        request.setContextPath("/context");
        var response = new MockHttpServletResponse(); var reached = new AtomicBoolean();
        filter().doFilter(request, response, (incoming, outgoing) -> reached.set(true));
        assertThat(reached).isFalse(); assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://opennest.example.invalid");
        assertThat(response.getContentAsString()).contains("The request is invalid.");
    }
    @Test void encodedListingPathEnforcesBodyLimit() throws Exception {
        var request = request("/%61pi/v1/listings", " ".repeat(32769));
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response, (incoming, outgoing) -> { throw new AssertionError("Limit bypassed"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://opennest.example.invalid");
    }
    @Test void similarlyPrefixedRouteDoesNotConsumeTheBody() throws Exception {
        var request = request("/api/v1/listings-diagnostic", "not JSON");
        var response = new MockHttpServletResponse(); var reached = new AtomicBoolean();
        filter().doFilter(request, response, (incoming, outgoing) -> reached.set(true));
        assertThat(reached).isTrue();
    }
    private MockHttpServletRequest request(String path, String body) {
        var request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json"); request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Origin", "https://opennest.example.invalid"); return request;
    }
}

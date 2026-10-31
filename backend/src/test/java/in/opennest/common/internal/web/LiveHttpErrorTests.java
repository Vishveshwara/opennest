package in.opennest.common.internal.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static in.opennest.common.internal.web.HttpContractFixtures.CANARY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_PASSWORD=fabricated-live-http-secret",
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@Import(HttpContractFixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class LiveHttpErrorTests {

    @Value("${local.server.port}") int port;
    @Autowired JsonMapper mapper;

    @AfterEach
    void excludesSensitiveMaterialFromLogs(CapturedOutput output) {
        assertThat(output).doesNotContain(CANARY, "Using generated security password", "fabricated-live-http-secret");
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/test-contract/container-error,503",
            "/api/v1/test-contract/filter-outage,503",
            "/api/v1/test-contract/filter-bug,500",
            "/api/v1/test-contract/missing,404",
            "/api/v1/users/me,401"
    })
    void returnsSafeProblemsThroughTheRealServletContainer(String path, int status) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "?token=" + CANARY))
                    .header("Accept", "text/html").header("Authorization", "Bearer " + CANARY).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.body()).doesNotContain(CANARY, "exception", "trace", "password");
            Map<String, Object> problem = mapper.readValue(response.body(), new TypeReference<>() { });
            assertThat(problem).containsOnlyKeys("type", "title", "status", "detail", "instance");
            assertThat(problem.get("status")).isEqualTo(status);
            assertThat(problem.get("type")).isEqualTo("about:blank");
            var errorId = UUID.fromString(response.headers().firstValue("X-Error-Id").orElseThrow());
            assertThat(problem.get("instance")).isEqualTo("urn:uuid:" + errorId);
        }
    }
}

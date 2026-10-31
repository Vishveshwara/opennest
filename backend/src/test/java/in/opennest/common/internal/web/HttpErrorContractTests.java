package in.opennest.common.internal.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static in.opennest.common.internal.web.HttpContractFixtures.CANARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = {
        "DB_PASSWORD=fabricated-http-contract-secret",
        "spring.security.user.name=not-a-login",
        "spring.security.user.password=not-a-password",
        "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(HttpContractFixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class HttpErrorContractTests {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired AuthenticationManager authenticationManager;

    @AfterEach
    void excludesSensitiveMaterialFromLogs(CapturedOutput output) {
        assertThat(output).doesNotContain(CANARY, "Using generated security password", "not-a-password", "fabricated-http-contract-secret");
    }

    @ParameterizedTest
    @CsvSource({
            "invalid,400", "unauthenticated,401", "forbidden,403", "missing,404", "conflict,409", "quota,429", "unavailable,503",
            "duplicate,409", "optimistic,409", "unique-sql,409", "hibernate-unique,409", "integrity-bug,500", "foreign-key-bug,500",
            "not-null-bug,500", "schema-bug,500", "database-outage,503", "resource-outage,503", "timeout,503", "query-timeout,503",
            "connection-state,503", "redis-outage,503", "redis-bug,500", "transaction-outage,503", "transaction-bug,500",
            "wrapped-bug,500", "argument-bug,500", "response-conflict,409", "response-bug,500", "custom-problem,409", "constraint-input,400",
            "constraint-return,500", "security-denied,401", "security-authentication,401", "unexpected,500"
    })
    void classifiesOnlyKnownFailuresAndKeepsTheContract(String scenario, int status) throws Exception {
        var result = mvc.perform(get("/api/v1/test-contract/failure/" + scenario)
                .param("token", CANARY).header("Authorization", "Bearer " + CANARY)
                .header("X-Error-Id", CANARY).accept("text/html")).andReturn();
        assertProblem(result, status);
    }

    @Test
    void rejectsMalformedJsonMissingBodiesAndValidationMessages() throws Exception {
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").with(csrf())
                .contentType("application/json").content("{\"name\":\"" + CANARY + "\", broken}")).andReturn(), 400);
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").with(csrf()).contentType("application/json")).andReturn(), 400);
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").with(csrf())
                .contentType("application/json").content("{\"name\":\"" + CANARY + "\",\"amount\":0}")).andReturn(), 400);
    }

    @Test
    void handlesMissingAndMalformedParametersAndMethodValidation() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/test-contract/number")).andReturn(), 400);
        assertProblem(mvc.perform(get("/api/v1/test-contract/number").param("number", CANARY)).andReturn(), 400);
        assertProblem(mvc.perform(get("/api/v1/test-contract/number").param("number", "0")).andReturn(), 400);
        assertProblem(mvc.perform(get("/api/v1/test-contract/uuid/" + CANARY)).andReturn(), 400);
        assertProblem(mvc.perform(get("/api/v1/test-contract/invalid-return")).andReturn(), 500);
    }

    @Test
    void standardizesMissingResourcesMethodsAndMediaFailures() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/test-contract/absent/" + CANARY)).andReturn(), 404);
        var method = mvc.perform(put("/api/v1/test-contract/number").with(csrf())).andReturn();
        assertProblem(method, 405);
        assertThat(method.getResponse().getHeader("Allow")).contains("GET");
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").with(csrf()).contentType("application/xml").content(CANARY)).andReturn(), 415);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/users/me", "/login", "/logout", "/oauth2/authorization/google", "/api/v1/diagnostics", "/actuator/health", "/error"})
    void deniesUnauthenticatedRoutesWithoutLoginPagesOrChallenges(String path) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", "Basic " + CANARY).accept("text/html")).andReturn();
        assertProblem(result, 401);
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void deniesEvenAnInjectedAuthenticatedPrincipalAndKeepsCsrfEnabled() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/users/me").with(user(CANARY).roles("ADMIN"))).andReturn(), 403);
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").contentType("application/json").content("{}")).andReturn(), 403);
        assertProblem(mvc.perform(post("/api/v1/test-contract/body").with(csrf().useInvalidToken()).contentType("application/json").content("{}")).andReturn(), 403);
    }

    @Test
    void installsNoPasswordUserAndRejectsAuthentication() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("not-a-login", "not-a-password")))
                .isInstanceOf(BadCredentialsException.class).hasMessage("Authentication is not configured");
    }

    @ParameterizedTest
    @CsvSource({"invalid,400,INFO", "unexpected,500,ERROR", "unavailable,503,ERROR"})
    void correlatesTheResponseWithASanitizedLogEvent(String scenario, int status, String level, CapturedOutput output) throws Exception {
        var result = mvc.perform(get("/api/v1/test-contract/failure/" + scenario)).andReturn();
        assertProblem(result, status);
        assertThat(output).contains(level + " OpenNest HTTP error status=" + status + " errorId="
                + result.getResponse().getHeader("X-Error-Id"));
    }

    @Test
    void delegatesControllerSecurityFailuresToSpringSecurity() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/test-contract/failure/security-denied")
                .with(user(CANARY))).andReturn(), 403);
        assertProblem(mvc.perform(get("/api/v1/test-contract/failure/security-authentication")
                .with(user(CANARY))).andReturn(), 401);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 410, 418, 422})
    void preservesClientStatusesFromFrameworkAndServletErrors(int status) throws Exception {
        assertProblem(mvc.perform(get("/api/v1/test-contract/status/" + status)).andReturn(), status);
        assertProblem(mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, CANARY)).andReturn(), status);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 499, 502})
    void normalizesUnrecognizedOrUnexpectedStatuses(int status) throws Exception {
        assertProblem(mvc.perform(get("/api/v1/test-contract/status/" + status)).andReturn(), 500);
        assertProblem(mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)).andReturn(), 500);
    }

    @Test
    void redactsFrameworkFailureAnalysisAndArbitraryDiagnosticMetadata(CapturedOutput output) {
        new LoggingFailureAnalysisReporter().report(new FailureAnalysis(CANARY, CANARY, new IllegalStateException(CANARY)));
        var failure = new IllegalStateException(CANARY, new RuntimeException(CANARY));
        failure.addSuppressed(new IllegalArgumentException(CANARY));
        try (var ignored = MDC.putCloseable("token", CANARY)) {
            LoggerFactory.getLogger(CANARY).atError().setCause(failure).addKeyValue("secret", CANARY).log("{}", CANARY);
        }
        assertThat(output).contains("ERROR OpenNest framework failure; diagnostic details redacted")
                .doesNotContain(CANARY, "IllegalStateException", "IllegalArgumentException");
    }

    @Test
    void redactsRealStartupFailureAfterLoggingInitialization(CapturedOutput output) {
        var application = new SpringApplication(StartupFailure.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        assertThatThrownBy(() -> application.run("--DB_PASSWORD=fabricated-startup-secret", "--spring.main.banner-mode=off"))
                .hasStackTraceContaining(CANARY);
        assertThat(output).contains("ERROR OpenNest framework failure; diagnostic details redacted")
                .doesNotContain(CANARY, "fabricated-startup-secret");
    }

    static class StartupFailure {

        @Bean
        String failingBean() {
            throw new IllegalStateException(CANARY);
        }
    }

    @Test
    void handlesFirewallFilterAndErrorDispatchFailures() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/blocked;" + CANARY)).andReturn(), 400);
        assertProblem(mvc.perform(get("/api/v1/test-contract/filter-outage")).andReturn(), 503);
        assertProblem(mvc.perform(get("/api/v1/test-contract/filter-bug")).andReturn(), 500);
        assertProblem(mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, CANARY)
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new RuntimeException(CANARY))).andReturn(), 503);
    }

    private void assertProblem(MvcResult result, int status) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Private-Data")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(CANARY, "exception", "trace", "password", "sql");
        Map<String, Object> body = mapper.readValue(response.getContentAsString(), new TypeReference<>() { });
        assertThat(body).containsOnlyKeys("type", "title", "status", "detail", "instance");
        assertThat(body.get("type")).isEqualTo("about:blank");
        assertThat(body.get("status")).isEqualTo(status);
        assertThat(body.get("title")).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(body.get("detail")).isEqualTo(Map.ofEntries(
                Map.entry(400, "The request is invalid."), Map.entry(401, "Authentication is required."),
                Map.entry(403, "Access is denied."), Map.entry(404, "The requested resource was not found."),
                Map.entry(405, "The request method is not allowed."), Map.entry(409, "The request conflicts with the current state."),
                Map.entry(408, "The request timed out."), Map.entry(410, "The requested resource is no longer available."),
                Map.entry(418, "The request could not be completed."), Map.entry(422, "The request could not be processed."),
                Map.entry(415, "The request media type is not supported."), Map.entry(429, "The request limit has been reached."),
                Map.entry(500, "An unexpected error occurred."), Map.entry(503, "The service is temporarily unavailable.")).get(status));
        var errorId = UUID.fromString(response.getHeader("X-Error-Id"));
        assertThat(body.get("instance")).isEqualTo("urn:uuid:" + errorId);
    }
}

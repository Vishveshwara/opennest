package in.opennest.common.internal.web;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.UUID;

import in.opennest.common.RequestFailure;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@TestConfiguration(proxyBeanMethods = false)
class HttpContractFixtures {

    static final String CANARY = "PRIVATE_SQL_PASSWORD_CONTACT_TOKEN_CANARY";

    @Bean
    @Order(0)
    SecurityFilterChain contractSecurity(HttpSecurity http, ProblemResponses responses) throws Exception {
        return http.securityMatcher("/api/v1/test-contract/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> responses.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> responses.write(response, 403)))
                .build();
    }

    @Bean
    FilterRegistrationBean<Filter> failingTestFilter() {
        Filter filter = (request, response, chain) -> {
            String path = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            if (path.equals("/api/v1/test-contract/filter-outage")) {
                throw new ServletException(CANARY, new CannotGetJdbcConnectionException(CANARY,
                        new SQLTransientConnectionException(CANARY)));
            }
            if (path.equals("/api/v1/test-contract/filter-bug")) {
                throw new IllegalStateException(CANARY, new SQLTransientConnectionException(CANARY));
            }
            chain.doFilter(request, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @RestController
    @TestComponent
    static class ContractController {

        private final Validator validator;

        ContractController(Validator validator) {
            this.validator = validator;
        }

        @GetMapping("/api/v1/test-contract/failure/{scenario}")
        void failure(@PathVariable String scenario, HttpServletResponse response) throws Exception {
            response.setHeader("X-Private-Data", CANARY);
            LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper").error(CANARY);
            LoggerFactory.getLogger("untrusted.library").error(CANARY, new SQLException(CANARY));
            throw switch (scenario) {
                case "invalid" -> new RequestFailure(RequestFailure.Kind.INVALID_INPUT);
                case "unauthenticated" -> new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
                case "forbidden" -> new RequestFailure(RequestFailure.Kind.FORBIDDEN);
                case "missing" -> new RequestFailure(RequestFailure.Kind.NOT_FOUND);
                case "conflict" -> new RequestFailure(RequestFailure.Kind.CONFLICT);
                case "quota" -> new RequestFailure(RequestFailure.Kind.RATE_LIMITED);
                case "unavailable" -> new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
                case "duplicate" -> new DuplicateKeyException(CANARY);
                case "optimistic" -> new OptimisticLockException(CANARY);
                case "unique-sql" -> new DataIntegrityViolationException(CANARY, new SQLException(CANARY, "23505"));
                case "hibernate-unique" -> new jakarta.persistence.PersistenceException(CANARY,
                        new org.hibernate.exception.ConstraintViolationException(CANARY, new SQLException(CANARY, "23505"), CANARY));
                case "integrity-bug" -> new DataIntegrityViolationException(CANARY);
                case "foreign-key-bug" -> new DataIntegrityViolationException(CANARY, new SQLException(CANARY, "23503"));
                case "not-null-bug" -> new DataIntegrityViolationException(CANARY, new SQLException(CANARY, "23502"));
                case "schema-bug" -> new SQLException(CANARY, "42P01");
                case "database-outage" -> new CannotGetJdbcConnectionException(CANARY, new SQLTransientConnectionException(CANARY));
                case "resource-outage" -> new DataAccessResourceFailureException(CANARY);
                case "timeout" -> new SQLTimeoutException(CANARY);
                case "query-timeout" -> new QueryTimeoutException(CANARY);
                case "connection-state" -> new SQLException(CANARY, "08006");
                case "redis-outage" -> new RedisConnectionFailureException(CANARY);
                case "redis-bug" -> new RedisSystemException(CANARY, new IllegalStateException(CANARY));
                case "transaction-outage" -> new CannotCreateTransactionException(CANARY, new SQLException(CANARY, "08001"));
                case "transaction-bug" -> new TransactionSystemException(CANARY, new IllegalStateException(CANARY));
                case "wrapped-bug" -> new IllegalStateException(CANARY, new DuplicateKeyException(CANARY));
                case "argument-bug" -> new IllegalArgumentException(CANARY);
                case "response-conflict" -> new ResponseStatusException(HttpStatus.CONFLICT, CANARY);
                case "response-bug" -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, CANARY);
                case "custom-problem" -> customProblem();
                case "constraint-input" -> new jakarta.validation.ConstraintViolationException(CANARY, Set.of());
                case "constraint-return" -> new ConstraintViolationException(CANARY,
                        validator.forExecutables().validateReturnValue(this,
                                ContractController.class.getDeclaredMethod("invalidReturn"), null));
                case "security-denied" -> new AccessDeniedException(CANARY);
                case "security-authentication" -> new BadCredentialsException(CANARY);
                default -> new IllegalStateException(CANARY);
            };
        }

        private ErrorResponseException customProblem() {
            var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CANARY);
            body.setTitle(CANARY);
            body.setProperty("token", CANARY);
            return new ErrorResponseException(HttpStatus.CONFLICT, body, new RuntimeException(CANARY));
        }

        @GetMapping("/api/v1/test-contract/status/{status}")
        void status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(status), CANARY);
        }

        @PostMapping(path = "/api/v1/test-contract/body", consumes = "application/json")
        void body(@Valid @RequestBody Input input) {
        }

        @GetMapping("/api/v1/test-contract/number")
        int number(@RequestParam @Min(value = 1, message = CANARY) int number) {
            return number;
        }

        @GetMapping("/api/v1/test-contract/uuid/{id}")
        UUID uuid(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/api/v1/test-contract/invalid-return")
        @NotNull(message = CANARY)
        String invalidReturn() {
            return null;
        }

        @GetMapping("/api/v1/test-contract/container-error")
        void containerError(HttpServletResponse response) throws java.io.IOException {
            response.sendError(503, CANARY);
        }

        record Input(@NotBlank(message = CANARY) String name, @Min(value = 1, message = CANARY) int amount) {
        }
    }
}

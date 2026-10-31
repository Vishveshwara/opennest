package in.opennest;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import in.opennest.common.internal.web.ProblemResponses;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@TestConfiguration(proxyBeanMethods = false)
@Import(IsolationProbeController.class)
class IsolationTestConfiguration {

    static final UUID MARKER = UUID.randomUUID();
    static final String KEY_PREFIX = "opennest:isolation:";

    @Bean
    @Order(0)
    SecurityFilterChain isolationProbeSecurity(HttpSecurity http, ProblemResponses responses) throws Exception {
        return http.securityMatcher("/api/v1/test-isolation/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ISOLATION_TEST"))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> responses.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> responses.write(response, 403)))
                .build();
    }

    @Bean
    LettuceClientConfigurationBuilderCustomizer boundedIsolationTestTimeouts() {
        return builder -> builder.commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO)
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2)).build()).build());
    }
}

@RestController
@TestComponent
class IsolationProbeController {

    private final JdbcTemplate database;
    private final StringRedisTemplate redis;

    IsolationProbeController(DataSource database, RedisConnectionFactory redis) {
        this.database = new JdbcTemplate(database);
        this.redis = new StringRedisTemplate(redis);
    }

    @GetMapping("/api/v1/test-isolation/database")
    Map<String, String> database() {
        return Map.of("value", database.queryForObject("SELECT full_name FROM public.users WHERE id=?",
                String.class, IsolationTestConfiguration.MARKER));
    }

    @GetMapping("/api/v1/test-isolation/redis")
    Map<String, String> redis() {
        return Map.of("value", redis.opsForValue().get(IsolationTestConfiguration.KEY_PREFIX
                + IsolationTestConfiguration.MARKER));
    }

    @PostMapping("/api/v1/test-isolation/write")
    void write(@RequestBody Marker marker) {
        database.update("INSERT INTO public.users(id,full_name) VALUES (?,?)", marker.id(), marker.value());
        redis.opsForValue().set(IsolationTestConfiguration.KEY_PREFIX + marker.id(), marker.value());
    }

    record Marker(UUID id, String value) {
    }
}

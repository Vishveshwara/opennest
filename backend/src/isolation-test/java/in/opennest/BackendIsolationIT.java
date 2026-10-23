package in.opennest;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class BackendIsolationIT {

    private static final String PREFIX = "/api/v1/test-isolation/";
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<MockMvc> requests = new ArrayList<>();
    private final Map<String, Map<String, String>> deploymentSettings = new LinkedHashMap<>();
    private final PostgreSQLContainer primaryDatabase = postgres("opennest_primary_isolation", "primary_migrator",
            "fabricated-primary-isolation-database-secret");
    private final PostgreSQLContainer sandboxDatabase = postgres("opennest_sandbox_isolation", "sandbox_migrator",
            "fabricated-sandbox-isolation-database-secret");
    private final String primaryRedisPassword = "fabricated-primary-isolation-redis-secret";
    private final String sandboxRedisPassword = "fabricated-sandbox-isolation-redis-secret";
    private final GenericContainer<?> primaryRedis = redis(primaryRedisPassword);
    private final GenericContainer<?> sandboxRedis = redis(sandboxRedisPassword);

    @BeforeAll
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void startIndependentDeployments() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Phase 1.8 requires a running Linux Docker engine; external-store fallbacks and skipped tests are forbidden")
                .isTrue();
        primaryDatabase.start();
        sandboxDatabase.start();
        primaryRedis.start();
        sandboxRedis.start();
        contexts.add(start("PRIMARY", Map.of()));
        contexts.add(start("SANDBOX", Map.of()));
        for (int target = 0; target < contexts.size(); target++) {
            assertThat(contexts.get(target).getBean(jakarta.persistence.EntityManagerFactory.class).getProperties()
                    .get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
            database(target).update("INSERT INTO public.users(id,full_name) VALUES (?,?)",
                    IsolationTestConfiguration.MARKER, mode(target));
            redis(target).opsForValue().set(markerKey(), mode(target));
            contexts.get(target).getBean(HikariDataSource.class).setConnectionTimeout(1000);
            requests.add(mvc(contexts.get(target)));
        }
    }

    @AfterAll
    void closeDeploymentsAndContainers() {
        try {
            contexts.reversed().forEach(ConfigurableApplicationContext::close);
        } finally {
            try {
                primaryRedis.close();
            } finally {
                try {
                    sandboxRedis.close();
                } finally {
                    try {
                        primaryDatabase.close();
                    } finally {
                        sandboxDatabase.close();
                    }
                }
            }
        }
    }

    @Test
    @Order(1)
    void installsIdenticalMigrationsAndConnectsOnlyConfiguredClients() {
        List<String> history = null;
        for (int target = 0; target < contexts.size(); target++) {
            assertThat(database(target).queryForObject("SELECT PostGIS_Lib_Version()", String.class)).startsWith("3.5.");
            assertThat(database(target).queryForObject("SHOW server_version_num", String.class)).startsWith("17");
            assertThat(contexts.get(target).getBean(Flyway.class).validateWithResult().validationSuccessful).isTrue();
            var current = database(target).queryForList("SELECT version || ':' || checksum FROM public.flyway_schema_history ORDER BY installed_rank", String.class);
            assertThat(current).containsExactly("1:-65807919", "2:842141308");
            if (history != null) assertThat(current).isEqualTo(history);
            history = current;
            var selected = contexts.get(target).getBean(LettuceConnectionFactory.class);
            assertThat(selected.getHostName()).isEqualTo(redisContainer(target).getHost());
            assertThat(selected.getPort()).isEqualTo(redisContainer(target).getMappedPort(6379));
            assertThat(contexts.get(target).getEnvironment().getProperty("spring.datasource.username"))
                    .isEqualTo(postgresContainer(target).getUsername());
            assertThat(contexts.get(target).getEnvironment().getProperty(target == 0 ? "SANDBOX_DB_PASSWORD" : "DB_PASSWORD"))
                    .isEmpty();
            assertThat(contexts.get(target).getEnvironment().getProperty(target == 0 ? "SANDBOX_REDIS_PASSWORD" : "REDIS_PASSWORD"))
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @Order(1)
    void readsAndWritesStayInConfiguredDeploymentDespiteHeaders(int target) throws Exception {
        for (String store : List.of("database", "redis")) {
            mvc(target).perform(get(PREFIX + store).with(user("fabricated-test-principal").roles("ISOLATION_TEST")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(mode(target)));
        }
        for (String untrustedMode : List.of("true", "false", "PRIMARY", "SANDBOX", "unexpected")) {
            for (String store : List.of("database", "redis")) {
                mvc(target).perform(get(PREFIX + store).header("X-Sandbox-Mode", untrustedMode)
                                .with(user("fabricated-test-principal").roles("ISOLATION_TEST")))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(mode(target)));
            }
            var id = UUID.randomUUID();
            mvc(target).perform(post(PREFIX + "write").header("X-Sandbox-Mode", untrustedMode)
                            .with(user("fabricated-test-principal").roles("ISOLATION_TEST")).with(csrf())
                            .contentType("application/json")
                            .content("{\"id\":\"" + id + "\",\"value\":\"fabricated-header-write\"}"))
                    .andExpect(status().isOk());
            assertThat(database(target).queryForObject("SELECT full_name FROM public.users WHERE id=?", String.class, id))
                    .isEqualTo("fabricated-header-write");
            assertThat(database(1 - target).queryForObject("SELECT count(*) FROM public.users WHERE id=?", Long.class, id)).isZero();
            assertThat(redis(target).opsForValue().get(IsolationTestConfiguration.KEY_PREFIX + id)).isEqualTo("fabricated-header-write");
            assertThat(redis(1 - target).hasKey(IsolationTestConfiguration.KEY_PREFIX + id)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @Order(1)
    void sandboxHeaderCannotGrantAuthenticationAuthorizationOrBypassCsrf(int target) throws Exception {
        for (String untrustedMode : List.of("true", "false", "SANDBOX")) {
            mvc(target).perform(get(PREFIX + "database").header("X-Sandbox-Mode", untrustedMode))
                    .andExpect(status().isUnauthorized());
            mvc(target).perform(get(PREFIX + "database").header("X-Sandbox-Mode", untrustedMode)
                            .with(user("fabricated-test-principal").roles("USER")))
                    .andExpect(status().isForbidden());
            mvc(target).perform(post(PREFIX + "write").header("X-Sandbox-Mode", untrustedMode)
                            .with(user("fabricated-test-principal").roles("ISOLATION_TEST"))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            for (String route : List.of("/api/v1/users/me", "/api/v1/sandbox/personas",
                    "/api/v1/sandbox/reset", "/api/v1/sandbox/telemetry", "/login")) {
                mvc(target).perform(get(route).header("X-Sandbox-Mode", untrustedMode))
                        .andExpect(status().isUnauthorized());
                mvc(target).perform(get(route).header("X-Sandbox-Mode", untrustedMode)
                                .with(user("fabricated-test-principal").roles("ADMIN")))
                        .andExpect(status().isForbidden());
            }
        }
    }

    @Test
    @Order(1)
    void sandboxDatabaseCredentialsCannotConnectToPrimaryEvenWhenPrimaryUserIsKnown() throws Exception {
        try (var connection = DriverManager.getConnection(primaryDatabase.getJdbcUrl(),
                primaryDatabase.getUsername(), primaryDatabase.getPassword())) {
            assertThat(connection.isValid(2)).isTrue();
        }
        for (String username : List.of(sandboxDatabase.getUsername(), primaryDatabase.getUsername())) {
            assertThatThrownBy(() -> {
                try (var ignored = DriverManager.getConnection(primaryDatabase.getJdbcUrl(), username, sandboxDatabase.getPassword())) {
                    throw new AssertionError("Sandbox credentials must not connect to primary");
                }
            }).isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState()).isEqualTo("28P01"));
        }
        assertSqlState(startupFailure("PRIMARY", Map.of("DB_PASSWORD", sandboxDatabase.getPassword())), "28P01");
        assertThat(database(0).queryForObject("SELECT full_name FROM public.users WHERE id=?", String.class,
                IsolationTestConfiguration.MARKER)).isEqualTo("PRIMARY");
    }

    @Test
    @Order(1)
    void sandboxRedisPasswordCannotReadPrimaryAndDoesNotTriggerFallback() throws Exception {
        var server = new RedisStandaloneConfiguration(primaryRedis.getHost(), primaryRedis.getMappedPort(6379));
        server.setPassword(sandboxRedisPassword);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build();
        var unauthorized = new LettuceConnectionFactory(server, client);
        try {
            unauthorized.afterPropertiesSet();
            unauthorized.start();
            assertThatThrownBy(() -> new StringRedisTemplate(unauthorized).opsForValue().get(markerKey()))
                    .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
        } finally {
            unauthorized.destroy();
        }
        try (var context = start("PRIMARY", Map.of("REDIS_PASSWORD", sandboxRedisPassword))) {
            unavailable(mvc(context), "redis");
        }
        assertThat(redis(0).opsForValue().get(markerKey())).isEqualTo("PRIMARY");
        assertThat(redis(1).opsForValue().get(markerKey())).isEqualTo("SANDBOX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENNEST_DEVELOPMENT_AUTH", "OPENNEST_PERSONA_SWITCHING", "OPENNEST_DEMO_RESET", "OPENNEST_DEMO_TELEMETRY"})
    @Order(1)
    void productionRejectsEveryDevelopmentOrDemoFlag(String flag) {
        assertThat(startupFailure("PRIMARY", Map.of(flag, "true"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production forbids");
    }

    @Test
    @Order(1)
    void productionRejectsSandboxModeAndSandboxSecrets() {
        assertThat(startupFailure("PRIMARY", Map.of("OPENNEST_DATA_MODE", "SANDBOX")))
                .hasMessage("Production requires PRIMARY data mode");
        for (var forbidden : List.of(Map.of("SANDBOX_DB_PASSWORD", sandboxDatabase.getPassword()),
                Map.of("SANDBOX_REDIS_PASSWORD", sandboxRedisPassword))) {
            assertThat(startupFailure("PRIMARY", forbidden)).hasMessage("Production must not receive sandbox datastore secrets");
        }
    }

    @Test
    @Order(1)
    void productionRegistersNoDemoOrLoginHandlers() {
        var arguments = deploymentSettings.get("PRIMARY").entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        try (var unmodified = new SpringApplication(OpenNestApplication.class).run(arguments)) {
            var handlers = unmodified.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values();
            assertThat(handlers).isNotEmpty().allSatisfy(handler -> assertThat(handler.getBeanType().getName())
                    .isEqualTo("in.opennest.common.internal.web.SafeErrorController"));
            assertThat(unmodified.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_HOST", "DB_NAME", "DB_USER", "DB_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD"})
    @Order(1)
    void missingRequiredProductionConfigurationFailsBeforeClients(String setting) {
        assertThat(startupFailure("PRIMARY", Map.of(setting, ""))).isInstanceOf(IllegalStateException.class);
        assertThat(redis(0).opsForValue().get(markerKey())).isEqualTo("PRIMARY");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @Order(1)
    void migrationChecksumMismatchPreventsStartupWithoutRepairOrCrossStoreChanges(int target) {
        var selected = database(target);
        selected.update("UPDATE public.flyway_schema_history SET checksum=0 WHERE version='1'");
        try {
            assertThat(startupFailure(mode(target), Map.of())).hasStackTraceContaining("checksum mismatch");
            assertThat(selected.queryForObject("SELECT checksum FROM public.flyway_schema_history WHERE version='1'", Integer.class)).isZero();
            assertThat(database(1 - target).queryForObject("SELECT checksum FROM public.flyway_schema_history WHERE version='1'", Integer.class))
                    .isEqualTo(-65807919);
        } finally {
            selected.update("UPDATE public.flyway_schema_history SET checksum=-65807919 WHERE version='1'");
        }
    }

    @Test
    @Order(1)
    void actualMigrationDdlFailurePreventsStartupAndRollsBackApplicationTables() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var name = "denied_" + suffix;
        try (var admin = DriverManager.getConnection(primaryDatabase.getJdbcUrl(), primaryDatabase.getUsername(), primaryDatabase.getPassword());
             var sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + name + " LOGIN PASSWORD 'fabricated-denied-migration-secret'");
            try {
                sql.execute("CREATE DATABASE " + name + " WITH TEMPLATE template0 OWNER " + name);
                try {
                    assertSqlState(startupFailure("PRIMARY", Map.of("DB_NAME", name, "DB_USER", name,
                            "DB_PASSWORD", "fabricated-denied-migration-secret")), "42501");
                    var url = "jdbc:postgresql://" + primaryDatabase.getHost() + ":" + primaryDatabase.getMappedPort(5432) + "/" + name;
                    try (var failed = DriverManager.getConnection(url, primaryDatabase.getUsername(), primaryDatabase.getPassword());
                         var query = failed.createStatement();
                         var result = query.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('users','auth_identities','properties','property_images','contact_reveals','owner_reviews')")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getLong(1)).isZero();
                    }
                } finally {
                    sql.execute("DROP DATABASE " + name + " WITH (FORCE)");
                }
            } finally {
                sql.execute("DROP ROLE " + name);
            }
        }
        assertThat(contexts.get(0).getBean(Flyway.class).validateWithResult().validationSuccessful).isTrue();
        assertThat(contexts.get(1).getBean(Flyway.class).validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @Order(100)
    void realDependencyOutagesFailClosedWhileOtherDeploymentRemainsAvailable() throws Exception {
        primaryRedis.stop();
        unavailable(mvc(0), "redis");
        mvc(0).perform(get(PREFIX + "database").with(user("fabricated-test-principal").roles("ISOLATION_TEST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("PRIMARY"));
        primaryDatabase.stop();
        unavailable(mvc(0), "database");
        assertSqlState(startupFailure("PRIMARY", Map.of()), "08001");
        for (String store : List.of("database", "redis")) {
            mvc(1).perform(get(PREFIX + store).header("X-Sandbox-Mode", "PRIMARY")
                            .with(user("fabricated-test-principal").roles("ISOLATION_TEST")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("SANDBOX"));
        }
        var id = UUID.randomUUID();
        mvc(1).perform(post(PREFIX + "write").with(user("fabricated-test-principal").roles("ISOLATION_TEST"))
                        .with(csrf()).contentType("application/json")
                        .content("{\"id\":\"" + id + "\",\"value\":\"fabricated-surviving-write\"}"))
                .andExpect(status().isOk());
        assertThat(database(1).queryForObject("SELECT full_name FROM public.users WHERE id=?", String.class, id))
                .isEqualTo("fabricated-surviving-write");
        assertThat(redis(1).opsForValue().get(IsolationTestConfiguration.KEY_PREFIX + id)).isEqualTo("fabricated-surviving-write");
    }

    private ConfigurableApplicationContext start(String mode, Map<String, String> overrides) {
        var settings = new LinkedHashMap<>(deploymentSettings.computeIfAbsent(mode, this::settings));
        settings.putAll(overrides);
        return new SpringApplication(OpenNestApplication.class, IsolationTestConfiguration.class).run(settings.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private Map<String, String> settings(String mode) {
        var settings = new LinkedHashMap<String, String>();
        settings.put("OPENNEST_ENVIRONMENT", mode.equals("PRIMARY") ? "PRODUCTION" : "DEVELOPMENT");
        settings.put("OPENNEST_DATA_MODE", mode);
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("spring.main.banner-mode", "off");
        for (String flag : List.of("OPENNEST_DEVELOPMENT_AUTH", "OPENNEST_PERSONA_SWITCHING", "OPENNEST_DEMO_RESET", "OPENNEST_DEMO_TELEMETRY")) {
            settings.put(flag, "false");
        }
        for (int target = 0; target < 2; target++) {
            var postgres = postgresContainer(target);
            var redis = redisContainer(target);
            var prefix = target == 0 ? "" : "SANDBOX_";
            boolean active = mode.equals(mode(target));
            settings.put(prefix + "DB_HOST", postgres.getHost());
            settings.put(prefix + "DB_PORT", postgres.getMappedPort(5432).toString());
            settings.put(prefix + "DB_NAME", postgres.getDatabaseName());
            settings.put(prefix + "DB_USER", postgres.getUsername());
            settings.put(prefix + "DB_PASSWORD", active ? postgres.getPassword() : "");
            settings.put(prefix + "REDIS_HOST", redis.getHost());
            settings.put(prefix + "REDIS_PORT", redis.getMappedPort(6379).toString());
            settings.put(prefix + "REDIS_USER", "");
            settings.put(prefix + "REDIS_PASSWORD", active ? (target == 0 ? primaryRedisPassword : sandboxRedisPassword) : "");
        }
        return settings;
    }

    private Throwable startupFailure(String mode, Map<String, String> overrides) {
        var failure = catchThrowable(() -> {
            try (var ignored = start(mode, overrides)) {
                throw new AssertionError("Unsafe or broken configuration must prevent startup");
            }
        });
        assertThat(failure).isNotNull().isNotInstanceOf(AssertionError.class);
        return failure;
    }

    private void assertSqlState(Throwable failure, String state) {
        var states = new ArrayList<String>();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) states.add(sql.getSQLState());
        }
        assertThat(states).contains(state);
    }

    private void unavailable(MockMvc mvc, String store) throws Exception {
        mvc.perform(get(PREFIX + store).header("X-Sandbox-Mode", "SANDBOX")
                        .with(user("fabricated-test-principal").roles("ISOLATION_TEST")))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The service is temporarily unavailable."));
    }

    private MockMvc mvc(int target) {
        return requests.get(target);
    }

    private MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private JdbcTemplate database(int target) {
        return new JdbcTemplate(contexts.get(target).getBean(HikariDataSource.class));
    }

    private StringRedisTemplate redis(int target) {
        return contexts.get(target).getBean(StringRedisTemplate.class);
    }

    private PostgreSQLContainer postgresContainer(int target) {
        return target == 0 ? primaryDatabase : sandboxDatabase;
    }

    private GenericContainer<?> redisContainer(int target) {
        return target == 0 ? primaryRedis : sandboxRedis;
    }

    private String mode(int target) {
        return target == 0 ? "PRIMARY" : "SANDBOX";
    }

    private String markerKey() {
        return IsolationTestConfiguration.KEY_PREFIX + IsolationTestConfiguration.MARKER;
    }

    private static PostgreSQLContainer postgres(String database, String username, String password) {
        return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database).withUsername(username).withPassword(password).withStartupTimeout(Duration.ofMinutes(2));
    }

    private static GenericContainer<?> redis(String password) {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", password, "--maxmemory-policy", "noeviction")
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(1));
    }
}

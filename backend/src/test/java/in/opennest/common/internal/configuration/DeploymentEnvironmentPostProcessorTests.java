package in.opennest.common.internal.configuration;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import in.opennest.OpenNestApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentEnvironmentPostProcessorTests {

    private final DeploymentEnvironmentPostProcessor processor = new DeploymentEnvironmentPostProcessor();

    @Test
    void selectsDevelopmentPrimaryByDefault() throws IOException {
        var environment = environment(Map.of("DB_PASSWORD", "fabricated-primary-secret"));
        process(environment);
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/opennest");
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6379");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("5");
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
    }

    @Test
    void selectsSandboxWithoutReceivingPrimarySecret() throws IOException {
        var environment = environment(Map.of("OPENNEST_DATA_MODE", "SANDBOX",
                "SANDBOX_DB_PASSWORD", "fabricated-sandbox-secret",
                "OPENNEST_PERSONA_SWITCHING", "true", "OPENNEST_DEMO_RESET", "true",
                "OPENNEST_DEMO_TELEMETRY", "true"));
        process(environment);
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5433/opennest_sandbox");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("opennest_sandbox");
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6380");
        assertThat(environment.getProperty("spring.data.redis.database")).isEqualTo("0");
    }

    @Test
    void acceptsProductionPrimaryWithRequiredSecrets() throws IOException {
        var environment = environment(production());
        process(environment);
        assertThat(environment.getProperty("spring.datasource.url")).endsWith("/opennest");
        assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("fabricated-redis-secret");
    }

    @Test
    void rejectsProductionSandboxMode() throws IOException {
        var values = production();
        values.put("OPENNEST_DATA_MODE", "SANDBOX");
        assertRejected(values, "Production requires PRIMARY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENNEST_DEVELOPMENT_AUTH", "OPENNEST_PERSONA_SWITCHING",
            "OPENNEST_DEMO_RESET", "OPENNEST_DEMO_TELEMETRY"})
    void rejectsEveryUnsafeProductionFlag(String flag) throws IOException {
        var values = production();
        values.put(flag, "true");
        assertRejected(values, "Production forbids");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENNEST_PERSONA_SWITCHING", "OPENNEST_DEMO_RESET", "OPENNEST_DEMO_TELEMETRY"})
    void rejectsDemoFlagsOnDevelopmentPrimary(String flag) throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", flag, "true"),
                "Demo operations require SANDBOX");
    }

    @Test
    void permitsExplicitDevelopmentAuthenticationOnlyInDevelopment() throws IOException {
        process(environment(Map.of("DB_PASSWORD", "fabricated-primary-secret",
                "OPENNEST_DEVELOPMENT_AUTH", "true")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SANDBOX_DB_PASSWORD", "SANDBOX_REDIS_PASSWORD"})
    void rejectsSandboxSecretsInProduction(String key) throws IOException {
        var values = production();
        values.put(key, "fabricated-other-sandbox-secret");
        assertRejected(values, "Production must not receive sandbox");
    }

    @Test
    void rejectsMissingActiveDatabasePassword() throws IOException {
        assertRejected(Map.of(), "Active database password is required");
        assertRejected(Map.of("OPENNEST_DATA_MODE", "SANDBOX", "DB_PASSWORD", "fabricated-primary-secret"),
                "Active database password is required");
    }

    @Test
    void rejectsMissingProductionRedisPassword() throws IOException {
        var values = production();
        values.remove("REDIS_PASSWORD");
        assertRejected(values, "Production requires a Redis password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_PORT", "REDIS_PORT"})
    void rejectsSharedStoreEndpointsEvenAcrossLoopbackAliases(String key) throws IOException {
        var values = new HashMap<String, Object>();
        values.put("DB_PASSWORD", "fabricated-primary-secret");
        values.put("SANDBOX_" + key, key.equals("DB_PORT") ? "5432" : "6379");
        values.put(key.equals("DB_PORT") ? "SANDBOX_DB_HOST" : "SANDBOX_REDIS_HOST", "localhost");
        assertRejected(values, "endpoints must differ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_USER", "DB_NAME", "DB_PASSWORD"})
    void rejectsSandboxDatabaseIdentityOrSecretReusedAsPrimary(String key) throws IOException {
        var values = new HashMap<String, Object>();
        values.put("DB_PASSWORD", "fabricated-primary-secret");
        values.put("SANDBOX_" + key, key.equals("DB_USER") || key.equals("DB_NAME")
                ? "opennest" : "fabricated-primary-secret");
        assertRejected(values, "must differ");
    }

    @Test
    void rejectsReusedRedisPasswords() throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", "REDIS_PASSWORD", "fabricated-redis-secret",
                "SANDBOX_REDIS_PASSWORD", "fabricated-redis-secret"), "Redis passwords must differ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url", "SPRING_DATASOURCE_PASSWORD", "spring.data.redis.url",
            "SPRING_DATA_REDIS_HOST", "SPRING_REDIS_PASSWORD", "spring.flyway.url", "SPRING_FLYWAY_USER",
            "spring.flyway.password", "spring.flyway.locations", "spring.flyway.enabled", "SPRING_FLYWAY_CLEAN_DISABLED",
            "spring.jpa.hibernate.ddl-auto", "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
            "hibernate.hbm2ddl.auto", "jakarta.persistence.schema-generation.database.action"})
    void rejectsAlternateSpringDatastoreConfiguration(String key) throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", key, "fabricated-override-secret"),
                "Use OpenNest deployment settings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENNEST_ENVIRONMENT", "OPENNEST_DATA_MODE", "OPENNEST_PERSONA_SWITCHING", "DB_PORT"})
    void rejectsUnknownEnumsAndMalformedTypesWithoutEchoingValues(String key) throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", key, "fabricated-invalid-secret"),
                "Invalid OpenNest deployment settings");
    }

    @Test
    void rejectsUnknownDeploymentPropertyNames() throws IOException {
        var environment = environment(Map.of("DB_PASSWORD", "fabricated-primary-secret"));
        environment.setProperty("opennest.deployment.features.persona-swiching", "true");
        assertThatThrownBy(() -> process(environment)).hasMessageContaining("Invalid OpenNest deployment settings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1"})
    void rejectsOutOfRangePorts(String port) throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", "DB_PORT", port), "Datastore port");
    }

    @Test
    void rejectsCredentialBearingOrMalformedTargets() throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", "DB_HOST", "user:secret@host"),
                "Datastore host is invalid");
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", "DB_NAME", "opennest?user=secret"),
                "Database name is invalid");
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", "REDIS_USER", "configured-user"),
                "Redis username requires a password");
    }

    @Test
    void unrelatedHeaderLikeValueDoesNotSelectSandbox() throws IOException {
        var environment = environment(Map.of("DB_PASSWORD", "fabricated-primary-secret", "X-Sandbox-Mode", "true"));
        process(environment);
        assertThat(environment.getProperty("spring.datasource.url")).endsWith(":5432/opennest");
    }

    @Test
    void redactsDatastoreDescriptions() {
        var database = new DeploymentProperties.Database("127.0.0.1", 5432, "private-name", "private-user", "private-secret");
        var redis = new DeploymentProperties.Redis("127.0.0.1", 6379, "private-redis-user", "private-redis-secret");
        assertThat(database.toString() + redis + new DeploymentProperties.Stores(database, redis))
                .doesNotContain("private-");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void springLaunchRejectsProductionDemoBeforeCreatingClientsWithoutLoggingSecrets(CapturedOutput output) {
        var application = new SpringApplication(OpenNestApplication.class);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        assertThatThrownBy(() -> application.run("--OPENNEST_ENVIRONMENT=PRODUCTION",
                "--OPENNEST_DATA_MODE=PRIMARY", "--OPENNEST_DEMO_RESET=true",
                "--DB_PASSWORD=fabricated-launch-database-secret",
                "--REDIS_PASSWORD=fabricated-launch-redis-secret"))
                .hasMessageContaining("Production forbids demo operations");
        assertThat(output).doesNotContain("fabricated-launch-");
    }

    private void assertRejected(Map<String, Object> values, String expected) throws IOException {
        var environment = environment(values);
        assertThatThrownBy(() -> process(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expected).satisfies(exception -> {
                    var output = new StringWriter();
                    exception.printStackTrace(new PrintWriter(output));
                    assertThat(output.toString()).doesNotContain("fabricated-", "user:secret", "?user=secret");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"logging.config", "LOGGING_LEVEL_ROOT", "server.error.include-message",
            "SERVER_ERROR_INCLUDE_STACKTRACE", "server.tomcat.accesslog.enabled", "spring.mvc.log-request-details"})
    void rejectsSettingsThatCouldExposeSensitiveErrorData(String key) throws IOException {
        assertRejected(Map.of("DB_PASSWORD", "fabricated-primary-secret", key, "fabricated-override-secret"),
                "OpenNest error and logging policy cannot be overridden");
    }

    private void process(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
    }

    private static MockEnvironment environment(Map<String, Object> values) throws IOException {
        var environment = new MockEnvironment();
        var yaml = new YamlPropertySourceLoader().load("deploymentDefaults", new ClassPathResource("application.yaml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("fixtureEnvironment", values));
        return environment;
    }

    private static Map<String, Object> production() {
        var values = new HashMap<String, Object>();
        values.put("OPENNEST_ENVIRONMENT", "PRODUCTION");
        values.put("DB_PASSWORD", "fabricated-primary-secret");
        values.put("REDIS_PASSWORD", "fabricated-redis-secret");
        return values;
    }
}

package in.opennest.identity.internal.configuration;

import in.opennest.OpenNestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class IdentitySettingsTests {

    @Test void sandboxPersonasEnableSeparateSessionsWithoutTelegram() {
        var environment = configured().withProperty("opennest.identity.telegram-enabled", "false")
                .withProperty("opennest.deployment.data-mode", "SANDBOX")
                .withProperty("opennest.deployment.features.persona-switching", "true");
        process(environment);
        assertThat(environment.getProperty("opennest.identity.sessions-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("opennest.identity.telegram-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.session.redis.namespace")).isEqualTo("opennest:development:sandbox:personas:v1:sessions");
    }

    @Test void rejectsSandboxOperatorCommandsInPrimary() {
        assertThatThrownBy(() -> process(configured().withProperty("opennest.sandbox.command", "reset")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dedicated development sandbox");
    }

    private static final String PRIVATE_CANARY = "fabricated-identity-private-canary";
    private static final String SAFE_FAILURE = "Invalid OpenNest identity settings; check credentials, origins and deployment isolation";

    @ParameterizedTest
    @CsvSource({
            "DEVELOPMENT,PRIMARY,development:primary,OPENNEST_SESSION",
            "DEVELOPMENT,SANDBOX,development:sandbox,OPENNEST_SANDBOX_SESSION",
            "PRODUCTION,PRIMARY,production:primary,__Host-OPENNEST_SESSION",
            "production,primary,production:primary,__Host-OPENNEST_SESSION",
            "development,sandbox,development:sandbox,OPENNEST_SANDBOX_SESSION",
            "Production,Primary,production:primary,__Host-OPENNEST_SESSION"
    })
    void isolatesSelectedOriginsCookieAndRedisNamespace(String runtime, String mode, String realm, String cookie) {
        var environment = configured().withProperty("opennest.deployment.environment", runtime)
                .withProperty("opennest.deployment.data-mode", mode);
        var settings = IdentitySettings.read(environment);
        assertThat(settings.selected(environment).backendOrigin())
                .isEqualTo(mode.equalsIgnoreCase("SANDBOX") ? "https://sandbox-api.opennest.example" : "https://api.opennest.example");
        assertThat(IdentitySettings.realm(environment)).isEqualTo(realm);
        assertThat(IdentitySettings.cookieName(environment)).isEqualTo(cookie);
        process(environment);
        assertThat(environment.getProperty("spring.session.redis.namespace")).isEqualTo("opennest:" + realm + ":telegram-auth:v1:sessions");
        assertThat(environment.getProperty("spring.session.timeout")).isEqualTo("30m");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://opennest.example/", "https://opennest.example/account",
            "https://user:password@opennest.example", "https://opennest.example?returnTo=external",
            "https://opennest.example#fragment", "https://*.opennest.example", "*",
            "//opennest.example", "http://opennest.example", "ftp://opennest.example",
            "https://opennest.example:0", "https://opennest.example:65536"
    })
    void rejectsNonOriginOrUnsafeUrlsForBothBrowserAndBackend(String value) {
        for (String key : new String[] {"backend-origin", "frontend-origin"}) {
            assertRejected(configured().withProperty("opennest.identity.primary." + key, value));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"})
    void permitsHttpOnlyForLocalDevelopment(String origin) {
        var environment = configured().withProperty("opennest.identity.primary.backend-origin", origin);
        assertThat(IdentitySettings.read(environment).selected(environment).backendOrigin()).isEqualTo(origin);
        environment.withProperty("opennest.deployment.environment", "PRODUCTION");
        assertRejected(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"backend-origin", "frontend-origin"})
    void requiresHttpsForEveryProductionOrigin(String key) {
        assertRejected(configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                .withProperty("opennest.identity.primary." + key, "http://localhost:8080"));
    }

    @Test
    void rejectsSharedPrimaryAndSandboxBackendOrigins() {
        var environment = configured().withProperty("opennest.identity.sandbox.backend-origin",
                "https://api.opennest.example");
        assertRejected(environment);
        assertRejected(environment.withProperty("opennest.deployment.data-mode", "SANDBOX"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"opennest.identity.google-enabled", "OPENNEST_GOOGLE_ENABLED"})
    void rejectsRemovedGoogleAuthenticationEnablement(String key) {
        assertRejected(configured().withProperty(key, "true"));
        assertThat(IdentitySettings.read(configured().withProperty(key, "false")).telegramEnabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE_CLIENT_SECRET", "SANDBOX_GOOGLE_CLIENT_SECRET",
            "opennest.identity.primary.client-secret", "opennest.identity.sandbox.client-secret"})
    void productionRejectsLegacyGoogleSecretsEvenWhenAuthenticationIsDisabled(String key) {
        for (String enabled : new String[] {"true", "false"}) {
            assertRejected(configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                    .withProperty("opennest.identity.telegram-enabled", enabled).withProperty(key, PRIVATE_CANARY));
        }
    }

    @Test
    void redactsConfigurationAndBindingFailures() {
        var environment = configured();
        var settings = IdentitySettings.read(environment);
        assertThat(settings.toString()).isEqualTo("IdentitySettings[redacted]");
        assertThat(settings.primary().toString()).isEqualTo("Origins[redacted]");
        assertThat(settings.sandbox().toString()).isEqualTo("Origins[redacted]");
        var failure = catchThrowable(() -> IdentitySettings.read(environment
                .withProperty("opennest.identity.telegram-enabled", PRIVATE_CANARY)));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(SAFE_FAILURE).hasNoCause();
        assertThat(failure.toString()).doesNotContain(PRIVATE_CANARY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.security.oauth2.client.registration.google.client-secret",
            "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET",
            "spring.session.redis.namespace", "SPRING_SESSION_REDIS_NAMESPACE",
            "spring.session.timeout", "spring.session.store-type",
            "server.servlet.session.cookie.name", "server.servlet.session.cookie.secure",
            "SERVER_SERVLET_SESSION_TIMEOUT", "server.forward-headers-strategy"
    })
    void rejectsDirectFrameworkAuthenticationAndSessionOverrides(String key) {
        var environment = configured().withProperty(key, PRIVATE_CANARY);
        assertThatThrownBy(() -> process(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Use OpenNest identity settings for authentication configuration")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "on", "1", "off", "no", "0", " true ", " false ", "TRUE", "FALSE"})
    void rejectsNonCanonicalFlagsBeforeSecurityConditions(String input) {
        assertRejected(configured().withProperty("opennest.identity.telegram-enabled", input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesCanonicalFlagsAndChoosesSessionStorage(boolean enabled) {
        var environment = configured().withProperty("opennest.identity.telegram-enabled", Boolean.toString(enabled));
        process(environment);
        assertThat(environment.getProperty("opennest.identity.telegram-enabled")).isEqualTo(Boolean.toString(enabled));
        assertThat(environment.getProperty("spring.session.store-type")).isEqualTo(enabled ? "redis" : "none");
    }

    private MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("opennest.deployment.environment", "DEVELOPMENT")
                .withProperty("opennest.deployment.data-mode", "PRIMARY")
                .withProperty("opennest.identity.telegram-enabled", "true")
                .withProperty("opennest.identity.primary.backend-origin", "https://api.opennest.example")
                .withProperty("opennest.identity.primary.frontend-origin", "https://opennest.example")
                .withProperty("opennest.identity.sandbox.backend-origin", "https://sandbox-api.opennest.example")
                .withProperty("opennest.identity.sandbox.frontend-origin", "https://sandbox.opennest.example");
    }

    private void assertRejected(MockEnvironment environment) {
        assertThatThrownBy(() -> IdentitySettings.read(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_FAILURE).hasNoCause();
    }

    private void process(MockEnvironment environment) {
        new IdentityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(OpenNestApplication.class));
    }
}

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

    private static final String PRIMARY_SECRET = "fabricated-primary-oauth-canary";
    private static final String SANDBOX_SECRET = "fabricated-sandbox-oauth-canary";
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
    void isolatesSelectedClientCookieAndRedisNamespace(String runtime, String mode, String realm, String cookie) {
        var environment = configured().withProperty("opennest.deployment.environment", runtime)
                .withProperty("opennest.deployment.data-mode", mode);
        if (runtime.equalsIgnoreCase("PRODUCTION")) environment.withProperty("opennest.identity.sandbox.client-secret", "");
        var settings = IdentitySettings.read(environment);
        assertThat(settings.selected(environment).clientId())
                .isEqualTo(mode.equalsIgnoreCase("SANDBOX") ? "fabricated-sandbox-client" : "fabricated-primary-client");
        assertThat(IdentitySettings.realm(environment)).isEqualTo(realm);
        assertThat(IdentitySettings.cookieName(environment)).isEqualTo(cookie);
        process(environment);
        assertThat(environment.getProperty("spring.session.redis.namespace")).isEqualTo("opennest:" + realm + ":sessions");
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
        environment.withProperty("opennest.deployment.environment", "PRODUCTION")
                .withProperty("opennest.identity.sandbox.client-secret", "");
        assertRejected(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"backend-origin", "frontend-origin"})
    void requiresHttpsForEveryProductionOrigin(String key) {
        var environment = configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                .withProperty("opennest.identity.sandbox.client-secret", "")
                .withProperty("opennest.identity.primary." + key, "http://localhost:8080");
        assertRejected(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"client-id", "client-secret"})
    void requiresCredentialsOnlyWhenGoogleLoginIsEnabled(String key) {
        var environment = configured().withProperty("opennest.identity.primary." + key, "");
        assertRejected(environment);
        environment.withProperty("opennest.identity.google-enabled", "false");
        assertThat(IdentitySettings.read(environment).googleEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"client-id", "client-secret", "backend-origin"})
    void rejectsSharedPrimaryAndSandboxIdentityConfiguration(String key) {
        var environment = configured();
        environment.withProperty("opennest.identity.sandbox." + key,
                environment.getRequiredProperty("opennest.identity.primary." + key));
        assertRejected(environment);
        environment.withProperty("opennest.deployment.data-mode", "SANDBOX");
        assertRejected(environment);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void productionRejectsSandboxSecretsEvenWhenGoogleLoginIsDisabled(boolean enabled) {
        assertRejected(configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                .withProperty("opennest.identity.google-enabled", Boolean.toString(enabled)));
    }

    @Test
    void redactsConfigurationAndBindingFailures() {
        var environment = configured();
        var settings = IdentitySettings.read(environment);
        assertThat(settings.toString()).isEqualTo("IdentitySettings[redacted]");
        assertThat(settings.primary().toString()).isEqualTo("Google[redacted]");
        assertThat(settings.sandbox().toString()).isEqualTo("Google[redacted]");
        var failure = catchThrowable(() -> IdentitySettings.read(environment
                .withProperty("opennest.identity.google-enabled", PRIMARY_SECRET)));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(SAFE_FAILURE).hasNoCause();
        assertThat(failure.toString()).doesNotContain(PRIMARY_SECRET, SANDBOX_SECRET);
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
        var environment = configured().withProperty(key, PRIMARY_SECRET);
        assertThatThrownBy(() -> process(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Use OpenNest identity settings for authentication configuration")
                .hasNoCause();
    }

    private MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("opennest.deployment.environment", "DEVELOPMENT")
                .withProperty("opennest.deployment.data-mode", "PRIMARY")
                .withProperty("opennest.identity.google-enabled", "true")
                .withProperty("opennest.identity.primary.client-id", "fabricated-primary-client")
                .withProperty("opennest.identity.primary.client-secret", PRIMARY_SECRET)
                .withProperty("opennest.identity.primary.backend-origin", "https://api.opennest.example")
                .withProperty("opennest.identity.primary.frontend-origin", "https://opennest.example")
                .withProperty("opennest.identity.sandbox.client-id", "fabricated-sandbox-client")
                .withProperty("opennest.identity.sandbox.client-secret", SANDBOX_SECRET)
                .withProperty("opennest.identity.sandbox.backend-origin", "https://sandbox-api.opennest.example")
                .withProperty("opennest.identity.sandbox.frontend-origin", "https://sandbox.opennest.example");
    }

    @ParameterizedTest
    @CsvSource({"yes,true", "on,true", "1,true", "off,false", "no,false", "0,false", "' true ',true", "' false ',false"})
    void canonicalizesBooleanAliasesBeforeSecurityConditions(String input, boolean expected) {
        var environment = configured().withProperty("opennest.identity.google-enabled", input);
        process(environment);
        assertThat(environment.getProperty("opennest.identity.google-enabled")).isEqualTo(Boolean.toString(expected));
    }

    private void assertRejected(MockEnvironment environment) {
        assertThatThrownBy(() -> IdentitySettings.read(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_FAILURE).hasNoCause();
    }

    private void process(MockEnvironment environment) {
        new IdentityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(OpenNestApplication.class));
    }
}

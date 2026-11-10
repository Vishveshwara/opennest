package in.opennest.identity.internal.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class TelegramSettingsTests {

    private static final String PRIMARY_TOKEN = "123456789:fabricated_primary_bot_token_canary";
    private static final String SANDBOX_TOKEN = "987654321:fabricated_sandbox_bot_token_canary";
    private static final String WEBHOOK_SECRET = "fabricated_primary_webhook_secret_canary";
    private static final String HMAC_KEY = "fabricated_primary_otp_hmac_key_canary";
    private static final String SAFE_FAILURE = "Invalid OpenNest Telegram settings; check credentials, transport and deployment isolation";

    @Test
    void defaultsToDisabledWebhookTransportWithoutCredentials() {
        var settings = new TelegramSettings(new MockEnvironment());
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.polling()).isFalse();
        assertThat(settings.production()).isFalse();
        assertThat(settings.realm()).isEqualTo("development:primary");
        assertThat(settings.username()).isEmpty();
        assertThat(settings.token()).isEmpty();
        assertThat(settings.webhookSecret()).isEmpty();
        assertThat(settings.hmacKey()).isEmpty();
    }

    @Test
    void readsEnvironmentFlagAndPrefersExplicitApplicationProperty() {
        var environment = new MockEnvironment().withProperty("OPENNEST_TELEGRAM_AUTH_ENABLED", "true")
                .withProperty("TELEGRAM_BOT_USERNAME", "OpenNestPrimaryBot")
                .withProperty("TELEGRAM_BOT_TOKEN", PRIMARY_TOKEN)
                .withProperty("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET)
                .withProperty("TELEGRAM_OTP_HMAC_KEY", HMAC_KEY);
        assertThat(new TelegramSettings(environment).enabled()).isTrue();
        assertThat(new TelegramSettings(environment.withProperty("opennest.identity.telegram-enabled", "false")).enabled()).isFalse();
    }

    @Test
    void selectsPrimaryCredentialsAndRetainsSelectionAfterConstruction() {
        var environment = configured();
        var settings = new TelegramSettings(environment);
        environment.withProperty("opennest.deployment.data-mode", "SANDBOX")
                .withProperty("TELEGRAM_BOT_TOKEN", SANDBOX_TOKEN);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.username()).isEqualTo("OpenNestPrimaryBot");
        assertThat(settings.token()).isEqualTo(PRIMARY_TOKEN);
        assertThat(settings.webhookSecret()).isEqualTo(WEBHOOK_SECRET);
        assertThat(settings.hmacKey()).isEqualTo(HMAC_KEY);
        assertThat(settings.realm()).isEqualTo("development:primary");
    }

    @Test
    void selectsSeparateSandboxConfigurationWhileContactActionsStayDisabled() {
        var environment = configured().withProperty("opennest.deployment.data-mode", "sandbox")
                .withProperty("opennest.identity.telegram-enabled", "false")
                .withProperty("SANDBOX_TELEGRAM_BOT_USERNAME", "OpenNestSandboxBot")
                .withProperty("SANDBOX_TELEGRAM_BOT_TOKEN", SANDBOX_TOKEN)
                .withProperty("SANDBOX_TELEGRAM_WEBHOOK_SECRET", "fabricated_sandbox_webhook_secret_canary")
                .withProperty("SANDBOX_TELEGRAM_OTP_HMAC_KEY", "fabricated_sandbox_otp_hmac_key_canary");
        var settings = new TelegramSettings(environment);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.realm()).isEqualTo("development:sandbox");
        assertThat(settings.username()).isEqualTo("OpenNestSandboxBot");
        assertThat(settings.token()).isEqualTo(SANDBOX_TOKEN);
        assertThat(settings.webhookSecret()).isEqualTo("fabricated_sandbox_webhook_secret_canary");
        assertThat(settings.hmacKey()).isEqualTo("fabricated_sandbox_otp_hmac_key_canary");
        assertRejected(environment.withProperty("opennest.identity.telegram-enabled", "true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "TRUE", " false ", "1", "", "fabricated-secret-canary"})
    void rejectsNoncanonicalFeatureAndTransportFlags(String value) {
        assertRejected(configured().withProperty("opennest.identity.telegram-enabled", value));
        assertRejected(configured().withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", value));
        assertRejected(new MockEnvironment().withProperty("OPENNEST_TELEGRAM_AUTH_ENABLED", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TELEGRAM_BOT_USERNAME", "TELEGRAM_BOT_TOKEN", "TELEGRAM_OTP_HMAC_KEY", "TELEGRAM_WEBHOOK_SECRET"})
    void requiresCompleteSelectedConfigurationWhenEnabled(String key) {
        assertRejected(configured().withProperty(key, ""));
        var disabled = configured().withProperty(key, "").withProperty("opennest.identity.telegram-enabled", "false");
        assertThat(new TelegramSettings(disabled).enabled()).isFalse();
    }

    @Test
    void allowsOnlyExplicitDevelopmentPolling() {
        var polling = configured().withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                .withProperty("TELEGRAM_WEBHOOK_SECRET", "");
        assertThat(new TelegramSettings(polling).polling()).isTrue();
        assertRejected(configured().withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                .withProperty("opennest.deployment.environment", "PRODUCTION"));
        var disabled = new TelegramSettings(configured().withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                .withProperty("opennest.identity.telegram-enabled", "false"));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.polling()).isTrue();
    }

    @Test
    void acceptsProductionWebhookAndCanonicalizesDeploymentNames() {
        var settings = new TelegramSettings(configured().withProperty("opennest.deployment.environment", " production ")
                .withProperty("opennest.deployment.data-mode", " primary "));
        assertThat(settings.production()).isTrue();
        assertThat(settings.realm()).isEqualTo("production:primary");
        assertThat(settings.polling()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SANDBOX_TELEGRAM_BOT_TOKEN", "SANDBOX_TELEGRAM_WEBHOOK_SECRET", "SANDBOX_TELEGRAM_OTP_HMAC_KEY"})
    void productionRejectsEverySandboxSecretEvenWhenDisabled(String key) {
        for (String enabled : new String[] {"true", "false"}) {
            assertRejected(configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                    .withProperty("opennest.identity.telegram-enabled", enabled).withProperty(key, "fabricated-sandbox-canary"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOT_USERNAME", "BOT_TOKEN", "WEBHOOK_SECRET", "OTP_HMAC_KEY"})
    void rejectsSharedPrimaryAndSandboxConfigurationEvenWhenDisabled(String suffix) {
        var environment = configured().withProperty("opennest.identity.telegram-enabled", "false");
        String shared = environment.getRequiredProperty("TELEGRAM_" + suffix);
        environment.withProperty("SANDBOX_TELEGRAM_" + suffix, shared);
        assertRejected(environment);
    }

    @Test
    void botNamesAreComparedWithoutCase() {
        assertRejected(configured().withProperty("SANDBOX_TELEGRAM_BOT_USERNAME", "opennestprimarybot"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"@OpenNestBot", "https://t.me/OpenNestBot", "bad/name", "bot", "1OpenNestBot", "OpenNest Bot"})
    void rejectsUnsafeUsernames(String username) {
        assertRejected(configured().withProperty("TELEGRAM_BOT_USERNAME", username));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-colon", "12345:short", "abcde:fabricated_primary_bot_token_canary", "12345:token with whitespace and more", "12345:fabricated/token/with/slashes"})
    void rejectsMalformedBotTokens(String token) {
        assertRejected(configured().withProperty("TELEGRAM_BOT_TOKEN", token));
    }

    @Test
    void measuresHmacKeyInUtf8Bytes() {
        assertRejected(configured().withProperty("TELEGRAM_OTP_HMAC_KEY", "a".repeat(31)));
        assertThat(new TelegramSettings(configured().withProperty("TELEGRAM_OTP_HMAC_KEY", "\u00e9".repeat(16))).hmacKey())
                .isEqualTo("\u00e9".repeat(16));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "invalid/secret/with/enough/characters", "invalid secret with enough characters"})
    void validatesConfiguredWebhookSecretIncludingPollingMode(String secret) {
        assertRejected(configured().withProperty("TELEGRAM_WEBHOOK_SECRET", secret));
        assertRejected(configured().withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                .withProperty("TELEGRAM_WEBHOOK_SECRET", secret));
    }

    @Test
    void enforcesWebhookSecretLengthBounds() {
        assertRejected(configured().withProperty("TELEGRAM_WEBHOOK_SECRET", "a".repeat(31)));
        assertRejected(configured().withProperty("TELEGRAM_WEBHOOK_SECRET", "a".repeat(257)));
        assertThat(new TelegramSettings(configured().withProperty("TELEGRAM_WEBHOOK_SECRET", "a".repeat(32))).enabled()).isTrue();
        assertThat(new TelegramSettings(configured().withProperty("TELEGRAM_WEBHOOK_SECRET", "a".repeat(256))).enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONSOLE_DEV", "TELEGRAM", "SMS", ""})
    void rejectsLegacyOrUndocumentedProviderAssertions(String value) {
        assertRejected(new MockEnvironment().withProperty("OTP_PROVIDER", value));
    }

    @Test
    void rejectsUnknownDeploymentModes() {
        assertRejected(configured().withProperty("opennest.deployment.environment", "STAGING"));
        assertRejected(configured().withProperty("opennest.deployment.data-mode", "DEMO"));
    }

    @Test
    void redactsConfigurationAndValidationFailures() {
        assertThat(new TelegramSettings(configured()).toString()).isEqualTo("TelegramSettings[redacted]");
        var failure = catchThrowable(() -> new TelegramSettings(configured()
                .withProperty("TELEGRAM_BOT_TOKEN", PRIMARY_TOKEN + "/unsafe")));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(SAFE_FAILURE).hasNoCause();
        assertThat(failure.toString()).doesNotContain(PRIMARY_TOKEN, SANDBOX_TOKEN, WEBHOOK_SECRET, HMAC_KEY);
    }

    private MockEnvironment configured() {
        return new MockEnvironment().withProperty("opennest.identity.telegram-enabled", "true")
                .withProperty("TELEGRAM_BOT_USERNAME", "OpenNestPrimaryBot")
                .withProperty("TELEGRAM_BOT_TOKEN", PRIMARY_TOKEN)
                .withProperty("TELEGRAM_WEBHOOK_SECRET", WEBHOOK_SECRET)
                .withProperty("TELEGRAM_OTP_HMAC_KEY", HMAC_KEY);
    }

    private void assertRejected(MockEnvironment environment) {
        assertThatThrownBy(() -> new TelegramSettings(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_FAILURE).hasNoCause();
    }
}

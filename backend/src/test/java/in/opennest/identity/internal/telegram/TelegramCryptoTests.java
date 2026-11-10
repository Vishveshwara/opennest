package in.opennest.identity.internal.telegram;

import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramCryptoTests {
    private static final String KEY = "fabricated_primary_otp_hmac_key_canary";

    @Test
    void generatesDistinctOpaqueTokensContainingThirtyTwoRandomBytes() {
        var crypto = crypto(configured());
        var tokens = new HashSet<String>();
        for (int index = 0; index < 512; index++) {
            String token = crypto.token();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}")
                    .doesNotContain("+910000000000", "OpenNestPrimaryBot", KEY, "fixture-user");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(tokens.add(token)).isTrue();
        }
        assertThat(tokens).hasSize(512);
    }

    @Test
    void hashesOpaqueTokensUsingTheStableSha256Vector() {
        var crypto = crypto(configured());
        assertThat(crypto.digest("hello")).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(crypto.digest("hello")).isEqualTo(crypto.digest("hello")).matches("[0-9a-f]{64}")
                .isNotEqualTo(crypto.digest("Hello"));
        assertThat(crypto.digest("hello")).isEqualTo(crypto(configured().withProperty("TELEGRAM_OTP_HMAC_KEY", "different_fabricated_hmac_key_canary")).digest("hello"));
    }

    @Test
    void producesStableKeyedDigestsSeparatedByPurposeValueSecretAndRealm() {
        var crypto = crypto(configured());
        String session = crypto.hmac("session", "fixture-value");
        assertThat(session).isEqualTo("74f87c48fbe5290cc962bf4e389088d3589ab7c0c069f928196ba9fd22dd814b")
                .matches("[0-9a-f]{64}").isEqualTo(crypto.hmac("session", "fixture-value"))
                .isNotEqualTo(crypto.hmac("phone", "fixture-value"))
                .isNotEqualTo(crypto.hmac("session", "fixture-value-changed"))
                .isNotEqualTo(crypto.digest("fixture-value"));
        assertThat(session).isNotEqualTo(crypto(configured().withProperty("TELEGRAM_OTP_HMAC_KEY", "different_fabricated_hmac_key_canary"))
                .hmac("session", "fixture-value"));
        assertThat(session).isNotEqualTo(crypto(production()).hmac("session", "fixture-value"));
    }

    @Test
    void generatesSixDigitCodesDeterministicallyFromTheSecretSaltAndRealm() {
        var crypto = crypto(configured());
        assertThat(crypto.code("fixture-salt-alpha")).isEqualTo("251546")
                .isEqualTo(crypto.code("fixture-salt-alpha"));
        assertThat(crypto.code("fixture-salt-13")).isEqualTo("026569");
        assertThat(crypto.code("fixture-salt-alpha"))
                .isNotEqualTo(crypto.code("fixture-salt-beta"))
                .isNotEqualTo(crypto(configured().withProperty("TELEGRAM_OTP_HMAC_KEY", "different_fabricated_hmac_key_canary")).code("fixture-salt-alpha"))
                .isNotEqualTo(crypto(production()).code("fixture-salt-alpha"));
        for (int index = 0; index < 256; index++) {
            assertThat(crypto.code("fixture-salt-" + index)).hasSize(6).matches("[0-9]{6}");
        }
    }

    private static TelegramCrypto crypto(MockEnvironment environment) { return new TelegramCrypto(new TelegramSettings(environment)); }

    private static MockEnvironment configured() {
        return new MockEnvironment().withProperty("opennest.identity.telegram-enabled", "true")
                .withProperty("opennest.identity.google-enabled", "true")
                .withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "true")
                .withProperty("TELEGRAM_BOT_USERNAME", "OpenNestPrimaryBot")
                .withProperty("TELEGRAM_BOT_TOKEN", "123456789:fabricated_primary_bot_token_canary")
                .withProperty("TELEGRAM_OTP_HMAC_KEY", KEY);
    }

    private static MockEnvironment production() {
        return configured().withProperty("opennest.deployment.environment", "PRODUCTION")
                .withProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "false")
                .withProperty("TELEGRAM_WEBHOOK_SECRET", "fabricated_primary_webhook_secret_canary");
    }
}

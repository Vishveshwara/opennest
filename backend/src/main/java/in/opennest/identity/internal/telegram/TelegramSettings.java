package in.opennest.identity.internal.telegram;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class TelegramSettings {

    private static final String SAFE_FAILURE = "Invalid OpenNest Telegram settings; check credentials, transport and deployment isolation";

    private final boolean enabled;
    private final boolean polling;
    private final boolean production;
    private final String realm;
    private final String username;
    private final String token;
    private final String webhookSecret;
    private final String hmacKey;

    public TelegramSettings(Environment environment) {
        try {
            enabled = flag(environment.getProperty("opennest.identity.telegram-enabled",
                    environment.getProperty("OPENNEST_TELEGRAM_AUTH_ENABLED", "false")));
            polling = flag(environment.getProperty("OPENNEST_TELEGRAM_POLLING_ENABLED", "false"));
            String runtime = environment.getProperty("opennest.deployment.environment", "DEVELOPMENT")
                    .strip().toUpperCase(Locale.ROOT);
            String mode = environment.getProperty("opennest.deployment.data-mode", "PRIMARY")
                    .strip().toUpperCase(Locale.ROOT);
            require(Set.of("DEVELOPMENT", "PRODUCTION").contains(runtime));
            require(Set.of("PRIMARY", "SANDBOX").contains(mode));
            production = runtime.equals("PRODUCTION");
            boolean sandbox = mode.equals("SANDBOX");
            realm = runtime.toLowerCase(Locale.ROOT) + ":" + mode.toLowerCase(Locale.ROOT);
            require(!environment.containsProperty("OTP_PROVIDER"));
            require(!production || !polling);

            String primaryUsername = value(environment, "TELEGRAM_BOT_USERNAME");
            String sandboxUsername = value(environment, "SANDBOX_TELEGRAM_BOT_USERNAME");
            String primaryToken = value(environment, "TELEGRAM_BOT_TOKEN");
            String sandboxToken = value(environment, "SANDBOX_TELEGRAM_BOT_TOKEN");
            String primarySecret = value(environment, "TELEGRAM_WEBHOOK_SECRET");
            String sandboxSecret = value(environment, "SANDBOX_TELEGRAM_WEBHOOK_SECRET");
            String primaryHmac = value(environment, "TELEGRAM_OTP_HMAC_KEY");
            String sandboxHmac = value(environment, "SANDBOX_TELEGRAM_OTP_HMAC_KEY");
            require(primaryUsername.isBlank() || sandboxUsername.isBlank()
                    || !primaryUsername.equalsIgnoreCase(sandboxUsername));
            distinct(primaryToken, sandboxToken);
            distinct(primarySecret, sandboxSecret);
            distinct(primaryHmac, sandboxHmac);
            require(!production || (sandboxToken.isBlank() && sandboxSecret.isBlank() && sandboxHmac.isBlank()));

            username = sandbox ? sandboxUsername : primaryUsername;
            token = sandbox ? sandboxToken : primaryToken;
            webhookSecret = sandbox ? sandboxSecret : primarySecret;
            hmacKey = sandbox ? sandboxHmac : primaryHmac;
            if (enabled) {
                require(!sandbox);
                require(username.matches("[A-Za-z][A-Za-z0-9_]{4,31}"));
                require(token.matches("[1-9][0-9]{4,19}:[A-Za-z0-9_-]{20,100}"));
                require(!hmacKey.isBlank() && hmacKey.getBytes(StandardCharsets.UTF_8).length >= 32);
                require(polling && webhookSecret.isBlank()
                        || webhookSecret.matches("[A-Za-z0-9_-]{32,256}"));
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException(SAFE_FAILURE);
        }
    }

    public boolean enabled() { return enabled; }
    public boolean polling() { return polling; }
    public boolean production() { return production; }
    public String realm() { return realm; }
    public String username() { return username; }
    public String token() { return token; }
    public String webhookSecret() { return webhookSecret; }
    public String hmacKey() { return hmacKey; }

    private static String value(Environment environment, String key) {
        return environment.getProperty(key, "");
    }

    private static boolean flag(String value) {
        require("true".equals(value) || "false".equals(value));
        return "true".equals(value);
    }

    private static void distinct(String primary, String sandbox) {
        require(primary.isBlank() || sandbox.isBlank() || !primary.equals(sandbox));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException(SAFE_FAILURE);
    }

    @Override public String toString() { return "TelegramSettings[redacted]"; }
}

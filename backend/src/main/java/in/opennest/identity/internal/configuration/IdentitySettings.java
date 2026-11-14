package in.opennest.identity.internal.configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

public record IdentitySettings(boolean telegramEnabled, Origins primary, Origins sandbox) {

    public record Origins(String backendOrigin, String frontendOrigin) {
        @Override public String toString() { return "Origins[redacted]"; }
    }

    /**
     * Validates identity flags and selected browser origins, returning fixed safe errors for invalid configuration.
     */
    public static IdentitySettings read(Environment environment) {
        try {
            String flag = environment.getProperty("opennest.identity.telegram-enabled",
                    environment.getProperty("OPENNEST_TELEGRAM_AUTH_ENABLED", "false"));
            require("true".equals(flag) || "false".equals(flag));
            for (String key : new String[] {"opennest.identity.google-enabled", "OPENNEST_GOOGLE_ENABLED"}) {
                String google = environment.getProperty(key, "false");
                require(google.isBlank() || "false".equalsIgnoreCase(google.strip()));
            }
            var settings = Binder.get(environment).bind("opennest.identity", Bindable.of(IdentitySettings.class))
                    .orElseThrow(IllegalStateException::new);
            settings = new IdentitySettings(Boolean.parseBoolean(flag), settings.primary(), settings.sandbox());
            boolean production = production(environment);
            developmentFrontendOrigins(environment);
            if (production) {
                for (String key : new String[] {"GOOGLE_CLIENT_SECRET", "SANDBOX_GOOGLE_CLIENT_SECRET",
                        "opennest.identity.primary.client-secret", "opennest.identity.sandbox.client-secret"}) {
                    require(environment.getProperty(key, "").isBlank());
                }
            }
            if (settings.telegramEnabled() || sandboxSessions(environment)) {
                var selected = settings.selected(environment);
                origin(selected.backendOrigin(), production);
                origin(selected.frontendOrigin(), production);
                require(!selected.backendOrigin().equals((sandbox(environment) ? settings.primary() : settings.sandbox()).backendOrigin()));
            }
            return settings;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid OpenNest identity settings; check credentials, origins and deployment isolation");
        }
    }

    public Origins selected(Environment environment) { return sandbox(environment) ? sandbox : primary; }
    public static List<String> developmentFrontendOrigins(Environment environment) {
        try {
            String configured = environment.getProperty("OPENNEST_DEV_LAN_ORIGINS", "").strip();
            if (configured.isEmpty()) return List.of();
            require(!production(environment) && !sandbox(environment));
            String[] origins = configured.split(",", -1);
            require(origins.length <= 8);
            return Arrays.stream(origins).map(String::strip).peek(value -> {
                var uri = URI.create(value);
                require("http".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                        && value.equals("http://" + uri.getRawAuthority()) && uri.getPort() != 0 && uri.getPort() <= 65535);
                String[] octets = uri.getHost().split("\\.", -1);
                require(octets.length == 4);
                int[] address = Arrays.stream(octets).mapToInt(part -> {
                    int number = Integer.parseInt(part);
                    require(number >= 0 && number <= 255 && Integer.toString(number).equals(part));
                    return number;
                }).toArray();
                require(address[0] == 10 || address[0] == 192 && address[1] == 168
                        || address[0] == 172 && address[1] >= 16 && address[1] <= 31);
            }).distinct().toList();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid OpenNest identity settings; check credentials, origins and deployment isolation");
        }
    }
    /**
     * Enables persona sessions only for explicitly opted-in development sandbox deployments.
     */
    public static boolean sandboxSessions(Environment environment) {
        return sandbox(environment) && !production(environment)
                && environment.getProperty("opennest.deployment.features.persona-switching", Boolean.class, false);
    }
    public static boolean production(Environment environment) {
        return "PRODUCTION".equalsIgnoreCase(environment.getProperty("opennest.deployment.environment", "DEVELOPMENT").strip());
    }
    public static boolean sandbox(Environment environment) {
        return "SANDBOX".equalsIgnoreCase(environment.getProperty("opennest.deployment.data-mode", "PRIMARY").strip());
    }
    public static String realm(Environment environment) {
        return (production(environment) ? "production" : "development") + ":" + (sandbox(environment) ? "sandbox" : "primary");
    }
    public static String cookieName(Environment environment) {
        return production(environment) ? "__Host-OPENNEST_SESSION" : sandbox(environment) ? "OPENNEST_SANDBOX_SESSION" : "OPENNEST_SESSION";
    }
    private static void origin(String value, boolean production) {
        var uri = URI.create(value);
        require(uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getPort() != 0 && uri.getPort() <= 65535);
        require("https".equals(uri.getScheme()) || (!production && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalStateException(); }
    @Override public String toString() { return "IdentitySettings[redacted]"; }
}

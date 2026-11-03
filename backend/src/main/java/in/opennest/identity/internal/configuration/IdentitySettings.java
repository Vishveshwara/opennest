package in.opennest.identity.internal.configuration;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

public record IdentitySettings(boolean googleEnabled, Google primary, Google sandbox) {

    public record Google(String clientId, String clientSecret, String backendOrigin, String frontendOrigin) {
        @Override public String toString() { return "Google[redacted]"; }
    }

    public static IdentitySettings read(Environment environment) {
        try {
            var settings = Binder.get(environment).bind("opennest.identity", Bindable.of(IdentitySettings.class))
                    .orElseThrow(IllegalStateException::new);
            boolean production = production(environment);
            require(!production || blank(settings.sandbox().clientSecret()), "Production forbids sandbox OAuth secrets");
            if (settings.googleEnabled()) {
                var google = settings.selected(environment);
                require(!blank(google.clientId()) && !blank(google.clientSecret()), "Google credentials are required");
                origin(google.backendOrigin(), production);
                origin(google.frontendOrigin(), production);
                require(!google.backendOrigin().equals((sandbox(environment) ? settings.primary() : settings.sandbox()).backendOrigin()),
                        "Primary and sandbox callback origins must differ");
                require(blank(settings.primary().clientId()) || blank(settings.sandbox().clientId())
                        || !settings.primary().clientId().equals(settings.sandbox().clientId()), "OAuth client IDs must differ");
                require(blank(settings.primary().clientSecret()) || blank(settings.sandbox().clientSecret())
                        || !settings.primary().clientSecret().equals(settings.sandbox().clientSecret()), "OAuth secrets must differ");
            }
            return settings;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid OpenNest identity settings; check credentials, origins and deployment isolation");
        }
    }

    public Google selected(Environment environment) { return sandbox(environment) ? sandbox : primary; }
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
                && uri.getPort() != 0 && uri.getPort() <= 65535, "Exact origins required");
        require("https".equals(uri.getScheme()) || (!production && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())), "HTTPS required outside local development");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    @Override public String toString() { return "IdentitySettings[redacted]"; }
}

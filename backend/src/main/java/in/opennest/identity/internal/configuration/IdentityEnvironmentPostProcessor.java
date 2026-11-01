package in.opennest.identity.internal.configuration;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

public class IdentityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    if (normalized.startsWith("springsecurityoauth2") || normalized.startsWith("springsession")
                            || normalized.startsWith("serverservletsession") || normalized.equals("serverforwardheadersstrategy")) {
                        throw new IllegalStateException("Use OpenNest identity settings for authentication configuration");
                    }
                }
            }
        }
        var settings = IdentitySettings.read(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("opennestIdentityPolicy", Map.of(
                "opennest.identity.google-enabled", settings.googleEnabled(),
                "spring.session.redis.namespace", "opennest:" + IdentitySettings.realm(environment) + ":sessions",
                "spring.session.timeout", "30m",
                "spring.session.redis.repository-type", "default",
                "spring.session.redis.flush-mode", "on_save",
                "server.forward-headers-strategy", "none",
                "spring.session.store-type", settings.googleEnabled() ? "redis" : "none")));
    }
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 2; }
}

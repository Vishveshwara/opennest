package in.opennest.common.internal.configuration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

public class DeploymentEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE = "opennestSelectedDatastores";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        rejectDatastoreOverrides(environment);
        DeploymentProperties deployment;
        try {
            deployment = Binder.get(environment).bind("opennest.deployment",
                    Bindable.of(DeploymentProperties.class),
                    new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseThrow(IllegalStateException::new);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid OpenNest deployment settings; check documented names and types");
        }
        DeploymentProperties.Stores stores = deployment.validatedStores();
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("opennest.deployment.environment", deployment.environment().name());
        selected.put("opennest.deployment.data-mode", deployment.dataMode().name());
        selected.put("spring.datasource.url", stores.database().jdbcUrl());
        selected.put("spring.datasource.username", stores.database().username());
        selected.put("spring.datasource.password", stores.database().password());
        selected.put("spring.datasource.hikari.maximum-pool-size", 5);
        selected.put("spring.datasource.hikari.minimum-idle", 1);
        selected.put("spring.sql.init.mode", "never");
        selected.put("server.error.include-message", "never");
        selected.put("server.error.include-stacktrace", "never");
        selected.put("server.error.include-binding-errors", "never");
        selected.put("server.error.include-exception", false);
        selected.put("server.error.whitelabel.enabled", false);
        selected.put("server.tomcat.accesslog.enabled", false);
        selected.put("spring.mvc.log-request-details", false);
        selected.put("spring.mvc.problemdetails.enabled", false);
        selected.put("logging.config", "classpath:logback-spring.xml");
        selected.put("logging.level.root", "ERROR");
        selected.put("logging.level.in.opennest.common.internal.web.ProblemResponses", "INFO");
        selected.put("debug", false);
        selected.put("trace", false);
        selected.put("spring.flyway.enabled", true);
        selected.put("spring.flyway.locations", "classpath:db/migration");
        selected.put("spring.flyway.default-schema", "public");
        selected.put("spring.flyway.schemas", "public");
        selected.put("spring.flyway.create-schemas", false);
        selected.put("spring.flyway.validate-on-migrate", true);
        selected.put("spring.flyway.validate-migration-naming", true);
        selected.put("spring.flyway.baseline-on-migrate", false);
        selected.put("spring.flyway.clean-disabled", true);
        selected.put("spring.jpa.hibernate.ddl-auto", "validate");
        selected.put("spring.jpa.open-in-view", false);
        selected.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
        selected.put("spring.jpa.properties.hibernate.type.json_format_mapper", "jackson3");
        selected.put("spring.data.jpa.repositories.enabled", false);
        selected.put("spring.data.redis.host", stores.redis().host());
        selected.put("spring.data.redis.port", stores.redis().port());
        selected.put("spring.data.redis.username", stores.redis().username());
        selected.put("spring.data.redis.password", stores.redis().password());
        selected.put("spring.data.redis.database", 0);
        selected.put("spring.data.redis.repositories.enabled", false);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, selected));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static void rejectDatastoreOverrides(ConfigurableEnvironment environment) {
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    if (normalized.startsWith("logging") || normalized.startsWith("servererror")
                            || normalized.startsWith("servertomcataccesslog") || normalized.equals("springmvclogrequestdetails")
                            || normalized.equals("springmvcproblemdetailsenabled")) {
                        throw new IllegalStateException("OpenNest error and logging policy cannot be overridden");
                    }
                    if (normalized.startsWith("springdatasource") || normalized.startsWith("springdataredis")
                            || normalized.startsWith("springredis") || normalized.startsWith("springflyway")
                            || normalized.startsWith("springjpa") || normalized.startsWith("hibernate")
                            || normalized.startsWith("jakartapersistence") || normalized.startsWith("javaxpersistence")) {
                        throw new IllegalStateException("Use OpenNest deployment settings for datastore configuration");
                    }
                }
            }
        }
    }
}

package in.opennest.common.internal.configuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

record DeploymentProperties(RuntimeEnvironment environment, DataMode dataMode, Features features,
                            Stores primary, Stores sandbox) {

    enum RuntimeEnvironment { DEVELOPMENT, PRODUCTION }

    enum DataMode { PRIMARY, SANDBOX }

    Stores validatedStores() {
        require(environment != null && dataMode != null && features != null
                && primary != null && sandbox != null, "Deployment settings are incomplete");
        require(primary.database() != null && primary.redis() != null
                && sandbox.database() != null && sandbox.redis() != null, "Datastore settings are incomplete");

        boolean production = environment == RuntimeEnvironment.PRODUCTION;
        require(!production || dataMode == DataMode.PRIMARY, "Production requires PRIMARY data mode");
        require(!production || !features.developmentAuth(), "Production forbids development authentication");
        require(!production || !features.hasDemoOperations(), "Production forbids demo operations");
        require(!features.hasDemoOperations() || dataMode == DataMode.SANDBOX,
                "Demo operations require SANDBOX data mode");

        Stores active = dataMode == DataMode.SANDBOX ? sandbox : primary;
        active.database().validate();
        active.redis().validate();
        require(!primary.database().endpoint().equals(sandbox.database().endpoint()),
                "Primary and sandbox PostgreSQL endpoints must differ");
        require(!primary.redis().endpoint().equals(sandbox.redis().endpoint()),
                "Primary and sandbox Redis endpoints must differ");
        require(!same(primary.database().username(), sandbox.database().username()),
                "Primary and sandbox PostgreSQL users must differ");
        require(!same(primary.database().name(), sandbox.database().name()),
                "Primary and sandbox database names must differ");
        require(!same(primary.database().password(), sandbox.database().password()),
                "Primary and sandbox PostgreSQL passwords must differ");
        require(!same(primary.redis().password(), sandbox.redis().password()),
                "Primary and sandbox Redis passwords must differ when configured");

        if (production) {
            require(present(primary.redis().password()), "Production requires a Redis password");
            require(!present(sandbox.database().password()) && !present(sandbox.redis().password()),
                    "Production must not receive sandbox datastore secrets");
        }
        return active;
    }

    record Features(boolean developmentAuth, boolean personaSwitching, boolean demoReset, boolean demoTelemetry) {

        boolean hasDemoOperations() {
            return personaSwitching || demoReset || demoTelemetry;
        }
    }

    record Stores(Database database, Redis redis) {

        @Override
        public String toString() {
            return "Stores[redacted]";
        }
    }

    record Database(String host, int port, String name, String username, String password) {

        void validate() {
            validateEndpoint(host, port);
            require(present(name) && name.matches("[A-Za-z0-9_-]+"), "Database name is invalid");
            require(present(username), "Active database username is required");
            require(present(password), "Active database password is required");
        }

        String jdbcUrl() {
            try {
                return "jdbc:" + new URI("postgresql", null, host, port, "/" + name, null, null).toASCIIString();
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Database endpoint is invalid");
            }
        }

        String endpoint() {
            return canonicalHost(host) + ":" + port;
        }

        @Override
        public String toString() {
            return "Database[redacted]";
        }
    }

    record Redis(String host, int port, String username, String password) {

        void validate() {
            validateEndpoint(host, port);
            require(!present(username) || present(password), "Redis username requires a password");
        }

        String endpoint() {
            return canonicalHost(host) + ":" + port;
        }

        @Override
        public String toString() {
            return "Redis[redacted]";
        }
    }

    private static void validateEndpoint(String host, int port) {
        require(present(host) && host.equals(host.trim()), "Datastore host is invalid");
        require(port >= 1 && port <= 65535, "Datastore port must be between 1 and 65535");
        try {
            require(new URI(null, null, host, port, null, null, null).getHost() != null,
                    "Datastore host is invalid");
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Datastore host is invalid");
        }
    }

    private static String canonicalHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(normalized)
                ? "loopback" : normalized;
    }

    private static boolean same(String first, String second) {
        return present(first) && present(second) && first.equals(second);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}

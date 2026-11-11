package in.opennest.sandbox.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "opennest.deployment.data-mode", havingValue = "SANDBOX")
class SandboxOperations implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SandboxBoundary boundary;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final TransactionTemplate transactions;

    SandboxOperations(JdbcTemplate jdbc, SandboxBoundary boundary, Environment environment,
            PlatformTransactionManager manager, ConfigurableApplicationContext context) {
        this.jdbc = jdbc; this.boundary = boundary; this.environment = environment; this.context = context;
        transactions = new TransactionTemplate(manager);
    }

    /**
     * Executes an authorized operator command and emits its JSON stdout result, or initializes enabled sandbox fixtures.
     */
    @Override public void run(ApplicationArguments arguments) {
        String command = environment.getProperty("opennest.sandbox.command", "");
        if (!command.isEmpty()) {
            if (!java.util.Set.of("reset", "telemetry").contains(command)) throw new IllegalStateException("Invalid OpenNest sandbox command");
            if (command.equals("reset")) reset(true);
            else requireFeature("demo-telemetry");
            System.out.println(JsonMapper.builder().build().writeValueAsString(telemetry()));
            SpringApplication.exit(context);
            return;
        }
        if (feature("persona-switching") || feature("demo-reset")) initialize();
    }

    /**
     * Seeds an empty dedicated sandbox once and rejects an incompatible existing fixture version.
     */
    void initialize() {
        transactions.executeWithoutResult(status -> {
            boundary.exclusive();
            if (jdbc.queryForObject("select count(*) from sandbox_state", Long.class) == 0) seed(Instant.now());
            else if (jdbc.queryForObject("select seed_version from sandbox_state where id = 1", Integer.class) != SandboxFixtures.VERSION)
                throw new IllegalStateException("OpenNest sandbox seed requires an operator reset");
        });
    }

    /**
     * Attempts the scheduled interval reset only while the sandbox reset feature is enabled.
     */
    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Kolkata")
    void scheduledReset() { if (feature("demo-reset")) reset(false); }

    /**
     * Checks reset authorization and atomically reseeds under an exclusive lock, deduplicating non-forced interval retries.
     */
    void reset(boolean force) {
        requireFeature("demo-reset");
        transactions.executeWithoutResult(status -> {
            boundary.exclusive();
            Instant now = Instant.now();
            var previous = jdbc.queryForList("select reset_interval from sandbox_state where id = 1", Long.class);
            if (!force && !previous.isEmpty() && previous.getFirst() == interval(now)) return;
            seed(now);
        });
    }

    /**
     * Replaces recognized sandbox fixtures in foreign-key order and advances their generation in the same transaction.
     */
    private void seed(Instant now) {
        var previous = jdbc.queryForList("select generation from sandbox_state where id = 1", Long.class);
        if (previous.isEmpty() && jdbc.queryForObject("select count(*) from users", Long.class) != 0)
            throw new IllegalStateException("OpenNest sandbox initialization requires an empty dedicated database");
        for (String table : new String[] {"telegram_login_receipts", "contact_verifications", "owner_reviews", "contact_reveals", "property_images", "properties", "auth_identities", "users"})
            jdbc.update("delete from " + table);
        new SandboxFixtures(jdbc).seed(now);
        long generation = previous.isEmpty() ? 1 : Math.addExact(previous.getFirst(), 1);
        jdbc.update("insert into sandbox_state (id, seed_version, generation, reset_interval, reset_at) values (1,?,?,?,?) on conflict (id) do update set seed_version=excluded.seed_version, generation=excluded.generation, reset_interval=excluded.reset_interval, reset_at=excluded.reset_at",
                SandboxFixtures.VERSION, generation, interval(now), Timestamp.from(now));
    }

    /**
     * Reads aggregate fixture counts and reset metadata under the shared reset guard without exposing private values.
     */
    Map<String, Object> telemetry() {
        return transactions.execute(status -> {
            boundary.lock();
            var result = new LinkedHashMap<String, Object>();
            for (String table : new String[] {"users", "properties", "property_images", "contact_reveals", "owner_reviews"})
                result.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
            var rows = jdbc.queryForList("select seed_version, generation, reset_at from sandbox_state where id = 1");
            result.put("initialized", !rows.isEmpty());
            if (!rows.isEmpty()) {
                result.put("seedVersion", rows.getFirst().get("seed_version"));
                result.put("generation", rows.getFirst().get("generation"));
                result.put("resetAt", ((Timestamp) rows.getFirst().get("reset_at")).toInstant());
            }
            return result;
        });
    }

    private boolean feature(String name) { return environment.getProperty("opennest.deployment.features." + name, Boolean.class, false); }
    /**
     * Rejects operator work unless both the dedicated sandbox mode and its required feature flag are active.
     */
    private void requireFeature(String name) { if (!boundary.sandbox() || !feature(name)) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN); }
    private static long interval(Instant now) { return (now.getEpochSecond() + 19800) / 21600; }
}

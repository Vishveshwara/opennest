package in.opennest.common;

import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class SandboxBoundary {
    public static final long LOCK_KEY = 726498103L;
    public static final String SESSION_GENERATION = "OPENNEST_SANDBOX_GENERATION";
    private final JdbcTemplate jdbc;
    private final boolean sandbox;

    public SandboxBoundary(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        sandbox = "SANDBOX".equals(environment.getProperty("opennest.deployment.data-mode", "PRIMARY"));
    }

    public boolean sandbox() { return sandbox; }

    /**
     * Holds the shared reset lock and rejects obsolete snapshots or request-session generations inside the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (!sandbox) return;
        jdbc.query("select pg_advisory_xact_lock_shared(?)", row -> {}, LOCK_KEY);
        java.util.List<Long> generations;
        try { generations = jdbc.queryForList("select generation from sandbox_state where id = 1 for share", Long.class); }
        catch (org.springframework.dao.ConcurrencyFailureException failure) { throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE); }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var session = attributes.getRequest().getSession(false);
            if (session != null && session.getAttribute(SESSION_GENERATION) != null
                    && (generations.isEmpty() || !Objects.equals(session.getAttribute(SESSION_GENERATION), generations.getFirst())))
                throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
        }
    }

    /**
     * Validates a session generation against committed metadata while holding a shared reset lock.
     */
    @Transactional(readOnly = true)
    public void validateGeneration(Object expected) {
        if (!sandbox) return;
        jdbc.query("select pg_advisory_xact_lock_shared(?)", row -> {}, LOCK_KEY);
        if (!Objects.equals(expected, generation())) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
    }

    /**
     * Reads the initialized sandbox generation or fails closed when reset metadata is absent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long generation() {
        var rows = jdbc.queryForList("select generation from sandbox_state where id = 1", Long.class);
        if (rows.isEmpty()) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        return rows.getFirst();
    }

    /**
     * Takes the transaction-scoped exclusive reset lock and forbids using it on a primary backend.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exclusive() {
        if (!sandbox) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
        jdbc.query("select pg_advisory_xact_lock(?)", row -> {}, LOCK_KEY);
    }
}

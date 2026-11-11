package in.opennest.interaction.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import in.opennest.common.SandboxBoundary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class InteractionStore {
    static final String ALLEGATION = "Brokerage reported. This is an allegation, not a finding of misconduct.";
    private final JdbcTemplate jdbc;
    private final SandboxBoundary boundary;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    InteractionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, SandboxBoundary boundary) {
        this.jdbc = jdbc; this.boundary = boundary;
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(!boundary.sandbox());
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    <T> T write(Supplier<T> work) { return execute(writes, work); }
    JdbcTemplate jdbc() { return jdbc; }
    <T> T read(Supplier<T> work) { return execute(reads, work); }
    /**
     * Runs a reset-guarded interaction transaction and retains the original application failure when rollback also fails.
     */
    private <T> T execute(TransactionTemplate template, Supplier<T> work) {
        var original = new RuntimeException[1];
        try {
            return template.execute(status -> {
                try { boundary.lock(); return work.get(); }
                catch (RuntimeException failure) { original[0] = failure; throw failure; }
            });
        } catch (RuntimeException failure) {
            if (original[0] != null && original[0] != failure) { original[0].addSuppressed(failure); throw original[0]; }
            throw failure;
        }
    }

    Instant now() { return jdbc.queryForObject("select clock_timestamp()", (row, index) -> row.getTimestamp(1).toInstant()); }
    boolean historical(UUID caller, UUID listing, UUID owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from contact_reveals where tenant_id = ? and property_id = ? and owner_id = ?)", Boolean.class, caller, listing, owner));
    }
    /**
     * Finds the caller/listing review and returns its safe response projection, or null when absent.
     */
    Map<String, Object> review(UUID caller, UUID listing) {
        var rows = jdbc.queryForList("select id, rating, issue_tag, comment_text, is_auto_broker_report, created_at from owner_reviews where reviewer_id = ? and property_id = ?", caller, listing);
        return rows.isEmpty() ? null : response(rows.getFirst());
    }
    /**
     * Projects public review fields and a separate allegation notice without exposing private account identifiers.
     */
    static Map<String, Object> response(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id")); result.put("rating", row.get("rating")); result.put("issueTag", row.get("issue_tag"));
        result.put("commentText", row.get("comment_text")); result.put("isAutoBrokerReport", row.get("is_auto_broker_report"));
        result.put("createdAt", ((java.sql.Timestamp) row.get("created_at")).toInstant());
        result.put("allegationNotice", Boolean.TRUE.equals(row.get("is_auto_broker_report")) ? ALLEGATION : null);
        return result;
    }
}

package in.opennest.identity.internal.persistence;

import java.math.BigDecimal;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.identity.InteractionAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class InteractionAccountStore implements InteractionAccounts {
    private final JdbcTemplate jdbc;
    InteractionAccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Serializes account mutations with a foreign-key-compatible row lock and rejects missing accounts.
     */
    @Override public void lock(UUID id) {
        if (jdbc.queryForList("select id from users where id = ? for no key update", UUID.class, id).isEmpty())
            throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
    }

    /**
     * Returns the verified owner contact to a trusted authorized caller or rejects unavailable contact evidence.
     */
    @Override public VerifiedContact verifiedContact(UUID id) {
        var rows = jdbc.queryForList("select phone_number from users where id = ? and phone_verified_at is not null and phone_number ~ '^\\+91[0-9]{10}$'", String.class, id);
        if (rows.isEmpty()) throw new RequestFailure(RequestFailure.Kind.CONTACT_VERIFICATION_REQUIRED);
        return new VerifiedContact(rows.getFirst());
    }

    /**
     * Assigns recomputed review aggregates inside the caller transaction and rejects a missing owner.
     */
    @Override public void updateReviewAggregates(UUID id, BigDecimal average, long reviews, long reports) {
        if (jdbc.update("update users set average_rating = ?, review_count = ?, broker_report_count = ? where id = ?",
                average, reviews, reports, id) != 1) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
    }
}

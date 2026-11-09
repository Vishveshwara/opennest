package in.opennest.identity.internal.persistence;

import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.identity.IdentityAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ListingAccounts implements IdentityAccounts {
    private final JdbcTemplate jdbc;

    ListingAccounts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean hasVerifiedContact(UUID id) {
        return jdbc.query("select phone_verified_at is not null and phone_number ~ '^\\+91[0-9]{10}$' from users where id = ?",
                (row, index) -> row.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lockForListingMutation(UUID id) {
        if (jdbc.query("select id from users where id = ? for update", (row, index) -> row.getObject(1, UUID.class), id).isEmpty())
            throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
    }
}

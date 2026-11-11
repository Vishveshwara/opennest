package in.opennest.listing.internal;

import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.listing.ListingInteractions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class InteractionListings implements ListingInteractions {
    private final JdbcTemplate jdbc;
    InteractionListings(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Finds immutable ownership and publication visibility, optionally holding a shared listing lock for contact reveal.
     */
    @Override public ListingSubject find(UUID id, boolean lock) {
        var rows = jdbc.query("select owner_id, status from properties where id = ?" + (lock ? " for share" : ""),
                (row, index) -> new ListingSubject(row.getObject(1, UUID.class), !"PAUSED".equals(row.getString(2))), id);
        if (rows.isEmpty()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return rows.getFirst();
    }
}

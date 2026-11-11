package in.opennest.identity;

import java.math.BigDecimal;
import java.util.UUID;

public interface InteractionAccounts {
    void lock(UUID id);
    VerifiedContact verifiedContact(UUID ownerId);
    void updateReviewAggregates(UUID ownerId, BigDecimal average, long reviews, long reports);

    record VerifiedContact(String phoneNumber) {
        @Override public String toString() { return "VerifiedContact[private]"; }
    }
}

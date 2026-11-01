package in.opennest.identity.internal.persistence;

import java.math.BigDecimal;
import java.util.UUID;
import in.opennest.identity.TrustBadge;

public record AccountProfile(UUID id, String fullName, String role, String phoneNumber, boolean phoneVerified,
                             BigDecimal averageRating, long reviewCount, long brokerReportCount, TrustBadge trustBadge) {
    @Override public String toString() { return "AccountProfile[private]"; }
}

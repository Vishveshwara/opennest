package in.opennest.identity;

import java.util.UUID;

public interface IdentityAccounts {
    boolean hasVerifiedContact(UUID userId);
}

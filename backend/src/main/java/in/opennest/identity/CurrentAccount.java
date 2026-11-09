package in.opennest.identity;

import java.util.Optional;
import java.util.UUID;

public interface CurrentAccount {
    Optional<UUID> id();
}

package in.opennest.identity.internal.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SessionPrincipal(UUID userId, String realm, Instant authenticatedAt) implements Serializable {
    @Override public String toString() { return "SessionPrincipal[private]"; }
}

package in.opennest.identity.internal.security;

import java.util.Optional;
import java.util.UUID;
import in.opennest.identity.CurrentAccount;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
final class AuthenticatedAccount implements CurrentAccount {
    @Override public Optional<UUID> id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionPrincipal principal
                ? Optional.of(principal.userId()) : Optional.empty();
    }
}

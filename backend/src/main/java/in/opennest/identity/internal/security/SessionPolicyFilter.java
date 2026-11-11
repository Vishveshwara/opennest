package in.opennest.identity.internal.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import in.opennest.common.HttpFailureResponse;
import in.opennest.common.SandboxBoundary;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionPolicyFilter extends OncePerRequestFilter {
    private final String realm;
    private final Clock clock;
    private final HttpFailureResponse errors;
    private final SandboxBoundary boundary;

    SessionPolicyFilter(String realm, Clock clock, HttpFailureResponse errors, SandboxBoundary boundary) {
        this.realm = realm;
        this.clock = clock;
        this.errors = errors;
        this.boundary = boundary;
    }

    /**
     * Rejects invalid realms, expired authentication and stale sandbox generations before dispatching the request.
     */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            boolean valid = authentication.getPrincipal() instanceof SessionPrincipal principal
                    && realm.equals(principal.realm()) && !principal.authenticatedAt().isAfter(clock.instant())
                    && clock.instant().isBefore(principal.authenticatedAt().plus(Duration.ofHours(24)));
            if (!valid) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                errors.write(response, 401);
                return;
            }
            if (boundary.sandbox()) {
                try {
                    var session = request.getSession(false);
                    boundary.validateGeneration(session == null ? null : session.getAttribute(SandboxBoundary.SESSION_GENERATION));
                } catch (in.opennest.common.RequestFailure failure) {
                    if (failure.kind() == in.opennest.common.RequestFailure.Kind.UNAUTHENTICATED) {
                        new SecurityContextLogoutHandler().logout(request, response, authentication);
                        errors.write(response, 401);
                    } else errors.write(response, 503);
                    return;
                } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
                    errors.write(response, failure); return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}

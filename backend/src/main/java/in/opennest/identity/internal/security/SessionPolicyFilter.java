package in.opennest.identity.internal.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import in.opennest.common.HttpFailureResponse;
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

    SessionPolicyFilter(String realm, Clock clock, HttpFailureResponse errors) {
        this.realm = realm;
        this.clock = clock;
        this.errors = errors;
    }

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
        }
        chain.doFilter(request, response);
    }
}

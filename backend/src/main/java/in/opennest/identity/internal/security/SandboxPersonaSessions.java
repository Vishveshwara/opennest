package in.opennest.identity.internal.security;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import in.opennest.identity.PersonaSessions;
import in.opennest.identity.internal.configuration.IdentitySettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@ConditionalOnProperty(name = "opennest.deployment.features.persona-switching", havingValue = "true")
class SandboxPersonaSessions implements PersonaSessions {
    private final Environment environment;
    private final HttpSessionSecurityContextRepository contexts;
    private final Clock clock;
    private final SandboxBoundary boundary;
    SandboxPersonaSessions(Environment environment, HttpSessionSecurityContextRepository contexts, Clock clock, SandboxBoundary boundary) {
        this.environment = environment; this.contexts = contexts; this.clock = clock; this.boundary = boundary;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void select(UUID id, long generation, HttpServletRequest request, HttpServletResponse response) {
        if (!IdentitySettings.sandboxSessions(environment)) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
        boundary.lock();
        if (boundary.generation() != generation) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(id, IdentitySettings.realm(environment), clock.instant()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        try {
            request.getSession();
            request.changeSessionId();
            new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()).onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.getSession().setAttribute(SandboxBoundary.SESSION_GENERATION, generation);
            contexts.saveContext(context, request, response);
        } catch (RuntimeException failure) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            throw failure;
        }
    }
}

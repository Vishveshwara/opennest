package in.opennest.identity.internal.telegram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.identity.internal.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramContactController {
    private final TelegramContacts contacts;
    private final TelegramUpdates updates;
    private final TelegramSettings settings;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final HttpSessionSecurityContextRepository contexts;
    private final Clock clock;
    TelegramContactController(TelegramContacts contacts, TelegramUpdates updates, TelegramSettings settings,
            HttpSessionSecurityContextRepository contexts, Clock clock) {
        this.contacts = contacts; this.updates = updates; this.settings = settings; this.contexts = contexts; this.clock = clock;
    }
    @PostMapping("/api/v1/auth/telegram/challenges")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) throws IOException {
        if (principal != null) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
        String session = session(request, null);
        var body = body(request, 8192); fields(body, Set.of("phoneNumber"));
        return response(contacts.create(session, string(body, "phoneNumber")));
    }
    @GetMapping("/api/v1/auth/telegram/challenges/{id}")
    ResponseEntity<Map<String, Object>> status(@PathVariable UUID id, HttpServletRequest request) {
        return response(contacts.status(id, session(request, id)));
    }
    @PostMapping("/api/v1/auth/telegram/challenges/{id}/complete")
    ResponseEntity<Map<String, Object>> complete(@PathVariable UUID id, @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request, HttpServletResponse servletResponse) throws IOException {
        if (principal != null && !id.toString().equals(request.getSession(false).getAttribute("OPENNEST_TELEGRAM_RETRY_ID")))
            throw new RequestFailure(RequestFailure.Kind.CONFLICT);
        String session = session(request, id);
        var body = body(request, 8192); fields(body, Set.of("code"));
        UUID user = contacts.complete(id, session, string(body, "code"));
        if (principal != null) {
            if (!user.equals(principal.userId())) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
            return response(Map.of("status", "AUTHENTICATED"));
        }
        var local = UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(user, settings.realm(), clock.instant()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        try {
            request.changeSessionId();
            new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()).onAuthentication(local, request, servletResponse);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(local);
            SecurityContextHolder.setContext(context);
            request.getSession().setAttribute("OPENNEST_TELEGRAM_RETRY_ID", id.toString());
            request.getSession().setAttribute("OPENNEST_TELEGRAM_RETRY_SESSION", session);
            contexts.saveContext(context, request, servletResponse);
        } catch (RuntimeException failure) {
            new SecurityContextLogoutHandler().logout(request, servletResponse, local);
            throw failure;
        }
        return response(Map.of("status", "AUTHENTICATED"));
    }
    @PostMapping("/api/v1/webhooks/telegram")
    ResponseEntity<Void> webhook(HttpServletRequest request) throws IOException {
        if (settings.polling()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        var headers = java.util.Collections.list(request.getHeaders("X-Telegram-Bot-Api-Secret-Token"));
        if (headers.size() != 1 || !MessageDigest.isEqual(headers.getFirst().getBytes(StandardCharsets.UTF_8), settings.webhookSecret().getBytes(StandardCharsets.UTF_8)))
            throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
        updates.accept(body(request, 65536));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private String session(HttpServletRequest request, UUID challenge) {
        var session = request.getSession(false);
        if (session == null) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
        if (challenge != null && challenge.toString().equals(session.getAttribute("OPENNEST_TELEGRAM_RETRY_ID")))
            return (String) session.getAttribute("OPENNEST_TELEGRAM_RETRY_SESSION");
        return contacts.session(session.getId());
    }
    private JsonNode body(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        try (var parser = json.createParser(bytes)) {
            var body = json.readTree(parser);
            if (body == null || !body.isObject() || parser.nextToken() != null) throw invalid();
            return body;
        } catch (tools.jackson.core.JacksonException failure) { throw invalid(); }
    }
    private static void fields(JsonNode body, Set<String> allowed) { for (String name : body.propertyNames()) if (!allowed.contains(name)) throw invalid(); }
    private static String string(JsonNode body, String name) {
        var value = body.get(name); if (value == null || !value.isString()) throw invalid(); return value.asString();
    }
    private static RequestFailure invalid() { return new RequestFailure(RequestFailure.Kind.INVALID_INPUT); }
    private ResponseEntity<Map<String, Object>> response(Map<String, Object> result) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result); }
}

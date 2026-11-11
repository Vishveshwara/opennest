package in.opennest.sandbox.internal;

import java.util.Map;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import in.opennest.identity.PersonaSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "opennest.deployment.features.persona-switching", havingValue = "true")
@RequestMapping("/api/v1/sandbox")
class SandboxController {
    private final SandboxBoundary boundary;
    private final PersonaSessions sessions;
    SandboxController(SandboxBoundary boundary, PersonaSessions sessions) { this.boundary = boundary; this.sessions = sessions; }

    @GetMapping("/personas") @Transactional
    public ResponseEntity<?> personas() {
        boundary.lock();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("personas", SandboxFixtures.PERSONAS.stream()
                .map(persona -> Map.of("key", persona.key(), "displayName", persona.displayName())).toList()));
    }

    /**
     * Validates the allowlisted persona and holds one reset guard through generation lookup and session installation.
     */
    @PostMapping("/personas/select") @Transactional
    public ResponseEntity<?> select(@RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("personaKey") || !body.get("personaKey").isString())
            throw new RequestFailure(RequestFailure.Kind.INVALID_INPUT);
        var persona = SandboxFixtures.PERSONAS.stream().filter(candidate -> candidate.key().equals(body.get("personaKey").stringValue()))
                .findFirst().orElseThrow(() -> new RequestFailure(RequestFailure.Kind.INVALID_INPUT));
        boundary.lock();
        sessions.select(persona.userId(), boundary.generation(), request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "AUTHENTICATED", "personaKey", persona.key()));
    }

    /**
     * Serves only allowlisted bundled illustrative PNG fixtures with no-store responses.
     */
    @GetMapping("/images/{segment}.png")
    ResponseEntity<?> image(@PathVariable String segment) {
        if (!SandboxFixtures.SEGMENTS.contains(segment)) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(new ClassPathResource("sandbox/images/" + segment + ".png"));
    }
}

package in.opennest.interaction.internal;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/interactions/listings/{id}")
class InteractionController {
    private final InteractionService interactions;
    InteractionController(InteractionService interactions) { this.interactions = interactions; }
    @PostMapping("/reveal-phone") ResponseEntity<Map<String, Object>> reveal(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) { return ok(interactions.reveal(id, body)); }
    @PostMapping("/reviews") ResponseEntity<Map<String, Object>> review(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(interactions.writeReview(id, body, false)); }
    @PostMapping("/report-brokerage") ResponseEntity<Map<String, Object>> report(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) { return ok(interactions.writeReview(id, body, true)); }
    @GetMapping("/reviews") ResponseEntity<Map<String, Object>> reviews(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) { return ok(interactions.reviews(id, query)); }
    @GetMapping("/state") ResponseEntity<Map<String, Object>> state(@PathVariable UUID id) { return ok(interactions.state(id)); }
    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

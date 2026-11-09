package in.opennest.listing.internal;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/listings")
@EnableScheduling
class ListingController {
    private final ListingService listings;
    private final ListingMedia media;
    ListingController(ListingService listings, ListingMedia media) { this.listings = listings; this.media = media; }

    @GetMapping("/search") ResponseEntity<Map<String, Object>> search(@RequestParam MultiValueMap<String, String> query) { return ok(listings.search(query, false)); }
    @GetMapping("/mine") ResponseEntity<Map<String, Object>> mine(@RequestParam MultiValueMap<String, String> query) { return ok(listings.search(query, true)); }
    @GetMapping("/{id}") ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) { return ok(listings.detail(id)); }
    @PostMapping ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        var created = listings.create(body);
        return ResponseEntity.created(URI.create("/api/v1/listings/" + created.get("id"))).cacheControl(CacheControl.noStore()).body(created);
    }
    @PostMapping("/{id}/publish") ResponseEntity<Map<String, Object>> publish(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(listings.change(id, body, "publish")); }
    @PostMapping("/{id}/pause") ResponseEntity<Map<String, Object>> pause(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(listings.change(id, body, "pause")); }
    @PatchMapping("/{id}/inventory") ResponseEntity<Map<String, Object>> inventory(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(listings.change(id, body, "inventory")); }
    @PostMapping("/{id}/images/presign") ResponseEntity<Map<String, Object>> presign(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(media.presign(id, listings.caller(), body)); }
    @PostMapping("/{id}/images/complete") ResponseEntity<Map<String, Object>> complete(@PathVariable UUID id, @RequestBody JsonNode body) { return ok(media.complete(id, listings.caller(), body)); }
    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

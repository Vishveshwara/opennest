package in.opennest.interaction.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.common.SandboxBoundary;
import in.opennest.identity.CurrentAccount;
import in.opennest.identity.InteractionAccounts;
import in.opennest.listing.ListingInteractions;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Service
class InteractionService {
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private final InteractionStore store;
    private final CurrentAccount current;
    private final InteractionAccounts accounts;
    private final ListingInteractions listings;
    private final SandboxBoundary boundary;

    InteractionService(InteractionStore store, CurrentAccount current, InteractionAccounts accounts,
            ListingInteractions listings, SandboxBoundary boundary) {
        this.store = store; this.current = current; this.accounts = accounts; this.listings = listings; this.boundary = boundary;
    }
    private UUID caller() { return current.id().orElseThrow(() -> new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED)); }

    /**
     * Reveals a published verified contact after a caller-row-serialized Kolkata quota check; same-day retries are free.
     */
    Map<String, Object> reveal(UUID id, JsonNode body) {
        UUID caller = caller(); InteractionInput.empty(body);
        if (boundary.sandbox()) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
        return store.write(() -> {
            accounts.lock(caller);
            var instant = store.now();
            LocalDate day = instant.atZone(KOLKATA).toLocalDate();
            var listing = listings.find(id, true);
            if (!listing.published()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
            if (caller.equals(listing.ownerId())) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
            var contact = accounts.verifiedContact(listing.ownerId());
            boolean revealed = revealedToday(caller, id, day);
            if (!revealed) {
                if (used(caller, day) >= 10) throw new RequestFailure(RequestFailure.Kind.RATE_LIMITED);
                store.jdbc().update("insert into contact_reveals (id, tenant_id, property_id, owner_id, created_at) values (?,?,?,?,?)",
                        UUID.randomUUID(), caller, id, listing.ownerId(), Timestamp.from(instant));
            }
            return Map.of("phoneNumber", contact.phoneNumber(), "whatsappUrl", "https://wa.me/" + contact.phoneNumber().substring(1),
                    "revealedDate", day, "remainingReveals", Math.max(0, 10 - used(caller, day)));
        });
    }

    /**
     * Creates a historically authorized review or converts it to an idempotent allegation while updating owner aggregates atomically.
     */
    Map<String, Object> writeReview(UUID id, JsonNode body, boolean report) {
        UUID caller = caller();
        var input = report ? null : InteractionInput.review(body);
        if (report) InteractionInput.empty(body);
        return store.write(() -> {
            var listing = listings.find(id, false);
            if (caller.equals(listing.ownerId())) throw new RequestFailure(RequestFailure.Kind.FORBIDDEN);
            accounts.lock(listing.ownerId());
            if (!store.historical(caller, id, listing.ownerId()))
                throw new RequestFailure(listing.published() ? RequestFailure.Kind.FORBIDDEN : RequestFailure.Kind.NOT_FOUND);
            var existing = store.review(caller, id);
            if (existing != null) {
                if (!report) throw new RequestFailure(RequestFailure.Kind.CONFLICT);
                if (Boolean.TRUE.equals(existing.get("isAutoBrokerReport"))) return existing;
                store.jdbc().update("update owner_reviews set rating = 1, issue_tag = 'ASKED_BROKERAGE', is_auto_broker_report = true where id = ?", existing.get("id"));
            } else {
                store.jdbc().update("insert into owner_reviews (id, property_id, owner_id, reviewer_id, rating, issue_tag, comment_text, is_auto_broker_report) values (?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), id, listing.ownerId(), caller, report ? 1 : input.rating(),
                        report ? "ASKED_BROKERAGE" : input.issueTag().name(), report ? InteractionStore.ALLEGATION : input.commentText(), report);
            }
            var aggregate = store.jdbc().queryForMap("select avg(rating) as average, count(*) as reviews, count(*) filter (where is_auto_broker_report) as reports from owner_reviews where owner_id = ?", listing.ownerId());
            accounts.updateReviewAggregates(listing.ownerId(), (BigDecimal) aggregate.get("average"),
                    ((Number) aggregate.get("reviews")).longValue(), ((Number) aggregate.get("reports")).longValue());
            return store.review(caller, id);
        });
    }

    /**
     * Reads one deterministic page of published-listing reviews and matching totals from the same snapshot.
     */
    Map<String, Object> reviews(UUID id, MultiValueMap<String, String> query) {
        var page = InteractionInput.page(query);
        return store.read(() -> {
            if (!listings.find(id, false).published()) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
            long total = store.jdbc().queryForObject("select count(*) from owner_reviews where property_id = ?", Long.class, id);
            var items = store.jdbc().queryForList("select id, rating, issue_tag, comment_text, is_auto_broker_report, created_at from owner_reviews where property_id = ? order by created_at desc, id asc limit ? offset ?", id, page.size(), (long) page.page() * page.size())
                    .stream().map(InteractionStore::response).toList();
            return Map.of("items", items, "page", page.page(), "size", page.size(), "totalElements", total,
                    "totalPages", (total + page.size() - 1) / page.size(), "hasNext", ((long) page.page() + 1) * page.size() < total,
                    "hasPrevious", page.page() > 0, "sort", "NEWEST");
        });
    }

    /**
     * Returns only the caller's reveal quota, review and eligibility state, preserving authorized history after pause.
     */
    Map<String, Object> state(UUID id) {
        UUID caller = caller();
        return store.read(() -> {
            var listing = listings.find(id, false);
            boolean history = store.historical(caller, id, listing.ownerId());
            boolean owner = caller.equals(listing.ownerId());
            if (!listing.published() && !history && !owner) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
            LocalDate day = store.now().atZone(KOLKATA).toLocalDate();
            var review = store.review(caller, id);
            var result = new LinkedHashMap<String, Object>();
            result.put("hasHistoricalReveal", history); result.put("revealedToday", revealedToday(caller, id, day));
            result.put("quotaDate", day); result.put("remainingReveals", boundary.sandbox() ? 0 : Math.max(0, 10 - used(caller, day)));
            result.put("contactActionsEnabled", !boundary.sandbox());
            result.put("canReview", history && !owner && review == null);
            result.put("canReportBrokerage", history && !owner && (review == null || !Boolean.TRUE.equals(review.get("isAutoBrokerReport"))));
            result.put("review", review);
            return result;
        });
    }

    private boolean revealedToday(UUID caller, UUID id, LocalDate day) {
        return Boolean.TRUE.equals(store.jdbc().queryForObject("select exists(select 1 from contact_reveals where tenant_id = ? and property_id = ? and revealed_date = ?)", Boolean.class, caller, id, day));
    }
    private long used(UUID caller, LocalDate day) {
        return store.jdbc().queryForObject("select count(*) from contact_reveals where tenant_id = ? and revealed_date = ?", Long.class, caller, day);
    }
}

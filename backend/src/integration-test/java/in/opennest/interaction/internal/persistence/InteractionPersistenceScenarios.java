package in.opennest.interaction.internal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DynamicTest;

import static in.opennest.support.PersistenceScenarioSupport.property;
import static in.opennest.support.PersistenceScenarioSupport.transaction;
import static in.opennest.support.PersistenceScenarioSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

public final class InteractionPersistenceScenarios {

    private InteractionPersistenceScenarios() {
    }

    public static List<DynamicTest> tests(EntityManagerFactory factory) {
        return List.of(
                dynamicTest("historical reveal instants and generated Kolkata dates round-trip", () -> transaction(factory, manager -> {
                    var owner = user(manager);
                    var tenant = user(manager);
                    var property = property(manager, owner);
                    for (String instant : List.of("2026-10-21T18:29:59.123456Z", "2026-10-21T18:30:00.123456Z")) {
                        var reveal = new ContactRevealEntity();
                        reveal.tenantId = tenant;
                        reveal.ownerId = owner;
                        reveal.propertyId = property;
                        reveal.createdAt = Instant.parse(instant);
                        reveal.revealedDate = LocalDate.of(2000, 1, 1);
                        manager.persist(reveal);
                        manager.flush();
                        var expected = reveal.createdAt.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                        assertThat(reveal.revealedDate).isEqualTo(expected);
                        manager.clear();
                        var loaded = manager.find(ContactRevealEntity.class, reveal.id);
                        assertThat(loaded.tenantId).isEqualTo(tenant);
                        assertThat(loaded.ownerId).isEqualTo(owner);
                        assertThat(loaded.propertyId).isEqualTo(property);
                        assertThat(loaded.createdAt).isEqualTo(reveal.createdAt);
                        assertThat(loaded.revealedDate).isEqualTo(expected);
                    }
                })),
                dynamicTest("review/report fields and immutable creation identity round-trip", () -> transaction(factory, manager -> {
                    var owner = user(manager);
                    var reviewer = user(manager);
                    var property = property(manager, owner);
                    var review = new OwnerReviewEntity();
                    review.ownerId = owner;
                    review.reviewerId = reviewer;
                    review.propertyId = property;
                    review.rating = 4;
                    review.issueTag = OwnerReviewEntity.IssueTag.GENUINE_OWNER;
                    review.commentText = "Fabricated preserved review text";
                    manager.persist(review);
                    manager.flush();
                    var created = review.createdAt;
                    manager.clear();
                    var loaded = manager.find(OwnerReviewEntity.class, review.id);
                    assertThat(loaded.ownerId).isEqualTo(owner);
                    assertThat(loaded.reviewerId).isEqualTo(reviewer);
                    assertThat(loaded.propertyId).isEqualTo(property);
                    assertThat(loaded.rating).isEqualTo((short) 4);
                    assertThat(loaded.issueTag).isEqualTo(OwnerReviewEntity.IssueTag.GENUINE_OWNER);
                    assertThat(loaded.autoBrokerReport).isFalse();
                    loaded.rating = 1;
                    loaded.issueTag = OwnerReviewEntity.IssueTag.ASKED_BROKERAGE;
                    loaded.autoBrokerReport = true;
                    manager.flush();
                    manager.clear();
                    var report = manager.find(OwnerReviewEntity.class, review.id);
                    assertThat(report.id).isEqualTo(review.id);
                    assertThat(report.createdAt).isEqualTo(created);
                    assertThat(report.commentText).isEqualTo(review.commentText);
                    assertThat(report.rating).isEqualTo((short) 1);
                    assertThat(report.issueTag).isEqualTo(OwnerReviewEntity.IssueTag.ASKED_BROKERAGE);
                    assertThat(report.autoBrokerReport).isTrue();
                })));
    }
}

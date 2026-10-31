package in.opennest.interaction.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "owner_reviews", schema = "public")
class OwnerReviewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "property_id", nullable = false, updatable = false)
    UUID propertyId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    UUID ownerId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    UUID reviewerId;

    @Column(nullable = false)
    short rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_tag", nullable = false, columnDefinition = "text")
    IssueTag issueTag;

    @Column(name = "comment_text", columnDefinition = "text")
    String commentText;

    @Column(name = "is_auto_broker_report", nullable = false)
    boolean autoBrokerReport;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    protected OwnerReviewEntity() {
    }

    enum IssueTag { GENUINE_OWNER, ASKED_BROKERAGE, MAINTENANCE_ISSUE, FAKE_PHOTOS, OTHER }
}

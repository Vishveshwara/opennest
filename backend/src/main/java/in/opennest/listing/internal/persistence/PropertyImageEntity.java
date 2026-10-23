package in.opennest.listing.internal.persistence;

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
@Table(name = "property_images", schema = "public")
class PropertyImageEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "property_id", nullable = false, updatable = false)
    UUID propertyId;

    @Column(name = "upload_object_key", nullable = false, updatable = false, columnDefinition = "text")
    String uploadObjectKey;

    @Column(name = "object_key", columnDefinition = "text")
    String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    Segment segment;

    @Column(name = "display_order", nullable = false)
    int displayOrder;

    @Column(name = "upload_expires_at", nullable = false)
    Instant uploadExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false, columnDefinition = "text")
    ValidationState validationState = ValidationState.PENDING;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    protected PropertyImageEntity() {
    }

    enum Segment { ROOM, HALL, BATHROOM, KITCHEN, EXTERIOR_AMENITY }
    enum ValidationState { PENDING, VALIDATED, REJECTED }
}

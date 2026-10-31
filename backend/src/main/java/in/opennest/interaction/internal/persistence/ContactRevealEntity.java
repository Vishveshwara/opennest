package in.opennest.interaction.internal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "contact_reveals", schema = "public")
class ContactRevealEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    UUID tenantId;

    @Column(name = "property_id", nullable = false, updatable = false)
    UUID propertyId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    UUID ownerId;

    @Generated
    @Column(name = "revealed_date", nullable = false, insertable = false, updatable = false)
    LocalDate revealedDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    protected ContactRevealEntity() {
    }
}

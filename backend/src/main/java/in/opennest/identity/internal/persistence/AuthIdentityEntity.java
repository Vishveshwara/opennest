package in.opennest.identity.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "auth_identities", schema = "public")
class AuthIdentityEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    UUID userId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    String issuer;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    String subject;

    @Column(columnDefinition = "text")
    String email;

    @Column(name = "email_verified", nullable = false)
    boolean emailVerified;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    protected AuthIdentityEntity() {
    }
}

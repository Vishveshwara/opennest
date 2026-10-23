package in.opennest.identity.internal.persistence;

import java.math.BigDecimal;
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
@Table(name = "users", schema = "public")
class UserEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "full_name", nullable = false, columnDefinition = "text")
    String fullName;

    @Column(name = "phone_number", length = 13)
    String phoneNumber;

    @Column(name = "phone_verified_at")
    Instant phoneVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    Role role = Role.USER;

    @Column(name = "average_rating", columnDefinition = "numeric")
    BigDecimal averageRating;

    @Column(name = "review_count", nullable = false)
    long reviewCount;

    @Column(name = "broker_report_count", nullable = false)
    long brokerReportCount;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    protected UserEntity() {
    }

    enum Role { USER }
}

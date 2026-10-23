package in.opennest.listing.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "properties", schema = "public")
class PropertyEntity {

    @Id
    @Column(nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "owner_id", nullable = false, updatable = false)
    UUID ownerId;

    @Column(nullable = false, columnDefinition = "text")
    String title;

    @Column(nullable = false, columnDefinition = "text")
    String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "flat_rental_mode", updatable = false, columnDefinition = "text")
    FlatRentalMode flatRentalMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    Status status = Status.PAUSED;

    @Version
    @Column(nullable = false)
    Long version;

    @Column(nullable = false, columnDefinition = "text")
    String city;

    @Column(nullable = false, columnDefinition = "text")
    String locality;

    @Column(name = "private_address", nullable = false, columnDefinition = "text")
    String privateAddress;

    @Column(nullable = false, precision = 9, scale = 6)
    BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    BigDecimal longitude;

    @Generated
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(nullable = false, insertable = false, updatable = false, columnDefinition = "geography(Point,4326)")
    Point location;

    @Column(name = "monthly_rent", nullable = false)
    int monthlyRent;

    @Column(name = "security_deposit", nullable = false)
    int securityDeposit;

    @Column
    Integer bedrooms;

    @Column(name = "has_wifi", nullable = false)
    boolean hasWifi;

    @Column(name = "has_gym", nullable = false)
    boolean hasGym;

    @Column(name = "has_swimming_pool", nullable = false)
    boolean hasSwimmingPool;

    @Column(name = "whole_flat_available")
    Boolean wholeFlatAvailable;

    @Column(name = "max_occupants")
    Integer maxOccupants;

    @Column(name = "male_rooms_available")
    Integer maleRoomsAvailable;

    @Column(name = "female_rooms_available")
    Integer femaleRoomsAvailable;

    @Column(name = "shared_rooms_available")
    Integer sharedRoomsAvailable;

    @Generated
    @Column(name = "total_rooms_available", insertable = false, updatable = false)
    Long totalRoomsAvailable;

    @Column(name = "max_sharing_per_room")
    Integer maxSharingPerRoom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nearby_landmarks", nullable = false, columnDefinition = "jsonb")
    String nearbyLandmarks = "[]";

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    @Generated
    @Column(name = "updated_at", nullable = false, insertable = false)
    Instant updatedAt;

    protected PropertyEntity() {
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    enum Category { FLAT, PG_HOSTEL }
    enum FlatRentalMode { ENTIRE_FLAT, ROOM }
    enum Status { ACTIVE, FULL_OCCUPIED, PAUSED }
}

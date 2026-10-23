package in.opennest.listing.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DynamicTest;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import static in.opennest.support.PersistenceScenarioSupport.transaction;
import static in.opennest.support.PersistenceScenarioSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

public final class ListingPersistenceScenarios {

    private ListingPersistenceScenarios() {
    }

    public static List<DynamicTest> tests(EntityManagerFactory factory) {
        var tests = new ArrayList<DynamicTest>();
        for (String mode : List.of("ENTIRE_FLAT", "ROOM", "PG_HOSTEL")) {
            tests.add(dynamicTest(mode + " round-trip with applicable NULLs and capacity", () -> transaction(factory, manager -> {
                var property = property(user(manager), mode);
                manager.persist(property);
                manager.flush();
                assertThat(property.createdAt).isNotNull();
                assertThat(property.updatedAt).isEqualTo(property.createdAt);
                assertThat(property.version).isZero();
                assertThat(property.location.getSRID()).isEqualTo(4326);
                assertThat(property.location.getX()).isEqualTo(77.5946);
                assertThat(property.location.getY()).isEqualTo(12.9716);
                manager.clear();
                var loaded = manager.find(PropertyEntity.class, property.id);
                assertThat(loaded.ownerId).isEqualTo(property.ownerId);
                assertThat(loaded.title).isEqualTo(property.title);
                assertThat(loaded.description).isEqualTo(property.description);
                assertThat(loaded.category).isEqualTo(property.category);
                assertThat(loaded.flatRentalMode).isEqualTo(property.flatRentalMode);
                assertThat(loaded.city).isEqualTo(property.city);
                assertThat(loaded.locality).isEqualTo(property.locality);
                assertThat(loaded.privateAddress).isEqualTo(property.privateAddress);
                assertThat(loaded.status).isEqualTo(PropertyEntity.Status.PAUSED);
                assertThat(loaded.monthlyRent).isEqualTo(10000);
                assertThat(loaded.securityDeposit).isEqualTo(0);
                assertThat(loaded.hasWifi).isTrue();
                assertThat(loaded.hasGym).isFalse();
                assertThat(loaded.hasSwimmingPool).isFalse();
                assertThat(loaded.nearbyLandmarks).isEqualTo("[]");
                if (mode.equals("ENTIRE_FLAT")) {
                    assertThat(loaded.wholeFlatAvailable).isTrue();
                    assertThat(loaded.maxOccupants).isEqualTo(4);
                    assertThat(loaded.maleRoomsAvailable).isNull();
                    assertThat(loaded.femaleRoomsAvailable).isNull();
                    assertThat(loaded.sharedRoomsAvailable).isNull();
                    assertThat(loaded.totalRoomsAvailable).isNull();
                    assertThat(loaded.maxSharingPerRoom).isNull();
                    assertThat(loaded.bedrooms).isZero();
                } else {
                    assertThat(loaded.wholeFlatAvailable).isNull();
                    assertThat(loaded.maxOccupants).isNull();
                    assertThat(loaded.maleRoomsAvailable).isEqualTo(1);
                    assertThat(loaded.femaleRoomsAvailable).isZero();
                    assertThat(loaded.sharedRoomsAvailable).isEqualTo(2);
                    assertThat(loaded.totalRoomsAvailable).isEqualTo(3L);
                    assertThat(loaded.maxSharingPerRoom).isEqualTo(2);
                    assertThat(loaded.bedrooms).isEqualTo(mode.equals("ROOM") ? 0 : null);
                }
            })));
        }
        tests.add(dynamicTest("generated geography/total stay read-only and refresh after updates", () -> transaction(factory, manager -> {
            var property = property(user(manager), "ROOM");
            manager.persist(property);
            manager.flush();
            var created = property.createdAt;
            property.totalRoomsAvailable = -1L;
            property.location = new GeometryFactory().createPoint(new Coordinate(0, 0));
            property.maleRoomsAvailable = Integer.MAX_VALUE;
            property.sharedRoomsAvailable = Integer.MAX_VALUE;
            property.latitude = new BigDecimal("18.520400");
            property.longitude = new BigDecimal("73.856700");
            property.nearbyLandmarks = "[{\"type\":\"SCHOOL\",\"name\":\"Fabricated test landmark\"}]";
            manager.flush();
            manager.refresh(property);
            assertThat(property.totalRoomsAvailable).isEqualTo(4294967294L);
            assertThat(property.location.getX()).isEqualTo(73.8567);
            assertThat(property.location.getY()).isEqualTo(18.5204);
            assertThat(property.version).isEqualTo(1L);
            manager.clear();
            var loaded = manager.find(PropertyEntity.class, property.id);
            assertThat(loaded.createdAt).isEqualTo(created);
            assertThat(loaded.updatedAt).isAfterOrEqualTo(created);
            assertThat(loaded.nearbyLandmarks).contains("Fabricated test landmark", "SCHOOL");
            assertThat(manager.createNativeQuery("SELECT jsonb_typeof(nearby_landmarks) FROM properties WHERE id=:id", String.class)
                    .setParameter("id", property.id).getSingleResult()).isEqualTo("array");
            assertThat(loaded.status).isEqualTo(PropertyEntity.Status.PAUSED);
        })));
        tests.add(dynamicTest("owner/category/mode cannot be reassigned through entity updates", () -> transaction(factory, manager -> {
            var property = property(user(manager), "ROOM");
            var otherOwner = user(manager);
            manager.persist(property);
            manager.flush();
            var owner = property.ownerId;
            property.ownerId = otherOwner;
            property.category = PropertyEntity.Category.PG_HOSTEL;
            property.flatRentalMode = null;
            property.title = "Fabricated changed title";
            manager.flush();
            manager.clear();
            var loaded = manager.find(PropertyEntity.class, property.id);
            assertThat(loaded.ownerId).isEqualTo(owner);
            assertThat(loaded.category).isEqualTo(PropertyEntity.Category.FLAT);
            assertThat(loaded.flatRentalMode).isEqualTo(PropertyEntity.FlatRentalMode.ROOM);
            assertThat(loaded.title).isEqualTo("Fabricated changed title");
        })));
        tests.add(dynamicTest("image reservation and final metadata round-trip", () -> transaction(factory, manager -> {
            var property = property(user(manager), "PG_HOSTEL");
            manager.persist(property);
            manager.flush();
            var image = new PropertyImageEntity();
            image.propertyId = property.id;
            image.uploadObjectKey = "staging/" + UUID.randomUUID();
            image.segment = PropertyImageEntity.Segment.ROOM;
            image.uploadExpiresAt = Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            manager.persist(image);
            manager.flush();
            assertThat(image.createdAt).isNotNull();
            manager.clear();
            var loaded = manager.find(PropertyImageEntity.class, image.id);
            assertThat(loaded.validationState).isEqualTo(PropertyImageEntity.ValidationState.PENDING);
            assertThat(loaded.objectKey).isNull();
            assertThat(loaded.displayOrder).isZero();
            assertThat(loaded.propertyId).isEqualTo(property.id);
            assertThat(loaded.uploadObjectKey).isEqualTo(image.uploadObjectKey);
            assertThat(loaded.uploadExpiresAt).isEqualTo(image.uploadExpiresAt);
            loaded.validationState = PropertyImageEntity.ValidationState.VALIDATED;
            loaded.objectKey = "final/" + UUID.randomUUID();
            loaded.segment = PropertyImageEntity.Segment.BATHROOM;
            loaded.displayOrder = 2;
            manager.flush();
            manager.clear();
            var validated = manager.find(PropertyImageEntity.class, image.id);
            assertThat(validated.objectKey).isEqualTo(loaded.objectKey);
            assertThat(validated.segment).isEqualTo(PropertyImageEntity.Segment.BATHROOM);
            assertThat(validated.displayOrder).isEqualTo(2);
            assertThat(validated.createdAt).isEqualTo(image.createdAt);
        })));
        tests.add(dynamicTest("two overlapping transactions reject a stale listing update", () -> optimisticConflict(factory)));
        return tests;
    }

    private static void optimisticConflict(EntityManagerFactory factory) {
        UUID owner;
        UUID id;
        try (var setup = factory.createEntityManager()) {
            setup.getTransaction().begin();
            owner = user(setup);
            var property = property(owner, "ROOM");
            id = property.id;
            setup.persist(property);
            setup.getTransaction().commit();
        }
        try (var first = factory.createEntityManager(); var second = factory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            try {
                var winner = first.find(PropertyEntity.class, id);
                var stale = second.find(PropertyEntity.class, id);
                assertThat(winner.version).isEqualTo(stale.version).isZero();
                winner.maleRoomsAvailable = 7;
                first.getTransaction().commit();
                stale.maleRoomsAvailable = 9;
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
                transaction(factory, manager -> {
                    var stored = manager.find(PropertyEntity.class, id);
                    assertThat(stored.maleRoomsAvailable).isEqualTo(7);
                    assertThat(stored.version).isEqualTo(1L);
                    assertThat(stored.totalRoomsAvailable).isEqualTo(9L);
                });
            } finally {
                if (first.getTransaction().isActive()) first.getTransaction().rollback();
                if (second.getTransaction().isActive()) second.getTransaction().rollback();
            }
        } finally {
            try (var cleanup = factory.createEntityManager()) {
                cleanup.getTransaction().begin();
                cleanup.createNativeQuery("DELETE FROM public.properties WHERE id=:id").setParameter("id", id).executeUpdate();
                cleanup.createNativeQuery("DELETE FROM public.users WHERE id=:id").setParameter("id", owner).executeUpdate();
                cleanup.getTransaction().commit();
            }
        }
    }

    private static PropertyEntity property(UUID owner, String mode) {
        var property = new PropertyEntity();
        property.ownerId = owner;
        property.title = "Fabricated persistence-test listing";
        property.description = "Fabricated test description";
        property.category = mode.equals("PG_HOSTEL") ? PropertyEntity.Category.PG_HOSTEL : PropertyEntity.Category.FLAT;
        property.flatRentalMode = mode.equals("PG_HOSTEL") ? null : PropertyEntity.FlatRentalMode.valueOf(mode);
        property.city = mode.equals("PG_HOSTEL") ? "HYDERABAD" : "PUNE";
        property.locality = "FABRICATED LOCALITY";
        property.privateAddress = "Fabricated private address";
        property.latitude = new BigDecimal("12.971600");
        property.longitude = new BigDecimal("77.594600");
        property.monthlyRent = 10000;
        property.hasWifi = true;
        if (!mode.equals("PG_HOSTEL")) property.bedrooms = 0;
        if (mode.equals("ENTIRE_FLAT")) {
            property.wholeFlatAvailable = true;
            property.maxOccupants = 4;
        } else {
            property.maleRoomsAvailable = 1;
            property.femaleRoomsAvailable = 0;
            property.sharedRoomsAvailable = 2;
            property.maxSharingPerRoom = 2;
        }
        return property;
    }
}

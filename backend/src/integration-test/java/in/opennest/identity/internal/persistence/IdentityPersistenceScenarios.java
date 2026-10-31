package in.opennest.identity.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DynamicTest;

import static in.opennest.support.PersistenceScenarioSupport.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

public final class IdentityPersistenceScenarios {

    private IdentityPersistenceScenarios() {
    }

    public static List<DynamicTest> tests(EntityManagerFactory factory) {
        return List.of(
                dynamicTest("phone-less account and provider identity round-trip", () -> transaction(factory, manager -> {
                    var user = new UserEntity();
                    user.fullName = "Fabricated identity-test account";
                    manager.persist(user);
                    manager.flush();
                    assertThat(user.createdAt).isNotNull();
                    var identity = new AuthIdentityEntity();
                    identity.userId = user.id;
                    identity.issuer = "https://accounts.google.com";
                    identity.subject = "fabricated-subject";
                    identity.email = "account@example.invalid";
                    identity.emailVerified = true;
                    manager.persist(identity);
                    manager.flush();
                    var created = identity.createdAt;
                    manager.clear();
                    var loaded = manager.find(UserEntity.class, user.id);
                    assertThat(loaded.fullName).isEqualTo(user.fullName);
                    assertThat(loaded.phoneNumber).isNull();
                    assertThat(loaded.phoneVerifiedAt).isNull();
                    assertThat(loaded.role).isEqualTo(UserEntity.Role.USER);
                    assertThat(loaded.averageRating).isNull();
                    assertThat(loaded.reviewCount).isZero();
                    assertThat(loaded.brokerReportCount).isZero();
                    var linked = manager.find(AuthIdentityEntity.class, identity.id);
                    assertThat(linked.userId).isEqualTo(user.id);
                    assertThat(linked.issuer).isEqualTo(identity.issuer);
                    assertThat(linked.subject).isEqualTo(identity.subject);
                    assertThat(linked.email).isEqualTo(identity.email);
                    assertThat(linked.emailVerified).isTrue();
                    assertThat(linked.createdAt).isEqualTo(created);
                    linked.email = null;
                    linked.emailVerified = false;
                    manager.flush();
                    manager.clear();
                    assertThat(manager.find(AuthIdentityEntity.class, identity.id).email).isNull();
                })),
                dynamicTest("verified contact and full precision numeric aggregate round-trip", () -> transaction(factory, manager -> {
                    var user = new UserEntity();
                    user.fullName = "Fabricated contact-test account";
                    user.phoneNumber = "+91" + "0".repeat(10);
                    user.phoneVerifiedAt = Instant.parse("2026-10-22T10:00:00.123456Z");
                    user.reviewCount = 3;
                    user.brokerReportCount = 1;
                    user.averageRating = new BigDecimal("3.1234567890123456");
                    manager.persist(user);
                    manager.flush();
                    var created = user.createdAt;
                    manager.clear();
                    var loaded = manager.find(UserEntity.class, user.id);
                    assertThat(loaded.phoneNumber).isEqualTo(user.phoneNumber);
                    assertThat(loaded.phoneVerifiedAt).isEqualTo(user.phoneVerifiedAt);
                    assertThat(loaded.averageRating).isEqualByComparingTo(user.averageRating);
                    assertThat(loaded.reviewCount).isEqualTo(3);
                    assertThat(loaded.brokerReportCount).isEqualTo(1);
                    assertThat(loaded.createdAt).isEqualTo(created);
                })));
    }
}

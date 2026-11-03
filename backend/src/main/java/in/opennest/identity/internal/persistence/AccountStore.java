package in.opennest.identity.internal.persistence;

import java.util.UUID;
import in.opennest.common.RequestFailure;
import in.opennest.identity.IdentityAccounts;
import in.opennest.identity.TrustBadge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "opennest.identity.google-enabled", havingValue = "true")
public class AccountStore implements IdentityAccounts {
    @PersistenceContext private EntityManager entityManager;
    private final TransactionTemplate transaction;

    public AccountStore(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID login(String issuer, String subject, String name, String email, boolean emailVerified) {
        try {
            return transaction.execute(status -> {
                var existing = identity(issuer, subject);
                if (existing != null) {
                    existing.email = email;
                    existing.emailVerified = email != null && emailVerified;
                    return existing.userId;
                }
                var user = new UserEntity();
                user.fullName = name == null || name.isBlank() ? "OpenNest user" : name.strip();
                entityManager.persist(user);
                var identity = new AuthIdentityEntity();
                identity.userId = user.id;
                identity.issuer = issuer;
                identity.subject = subject;
                identity.email = email;
                identity.emailVerified = email != null && emailVerified;
                entityManager.persist(identity);
                entityManager.flush();
                return user.id;
            });
        } catch (RuntimeException failure) {
            if (!identityConflict(failure)) throw failure;
            return transaction.execute(status -> {
                var winner = identity(issuer, subject);
                if (winner == null) throw failure;
                return winner.userId;
            });
        }
    }

    @Transactional(readOnly = true)
    public AccountProfile profile(UUID id) {
        var user = entityManager.find(UserEntity.class, id);
        if (user == null) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
        return new AccountProfile(user.id, user.fullName, user.role.name(), user.phoneNumber, verified(user),
                user.averageRating, user.reviewCount, user.brokerReportCount,
                TrustBadge.calculate(user.averageRating, user.reviewCount, user.brokerReportCount));
    }

    @Override @Transactional(readOnly = true)
    public boolean hasVerifiedContact(UUID userId) {
        var user = entityManager.find(UserEntity.class, userId);
        return user != null && verified(user);
    }

    private boolean verified(UserEntity user) {
        return user.phoneVerifiedAt != null && user.phoneNumber != null && user.phoneNumber.matches("\\+91[0-9]{10}");
    }

    private AuthIdentityEntity identity(String issuer, String subject) {
        return entityManager.createQuery("select i from AuthIdentityEntity i where i.issuer = :issuer and i.subject = :subject", AuthIdentityEntity.class)
                .setParameter("issuer", issuer).setParameter("subject", subject).getResultStream().findFirst().orElse(null);
    }

    private boolean identityConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uq_auth_identities_issuer_subject".equals(constraint.getConstraintName())) return true;
        }
        return false;
    }
}

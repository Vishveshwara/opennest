package in.opennest.identity.internal.persistence;

import java.util.UUID;
import java.util.function.Supplier;
import in.opennest.common.RequestFailure;
import in.opennest.identity.IdentityAccounts;
import in.opennest.identity.TrustBadge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "opennest.identity.google-enabled", havingValue = "true")
public class AccountStore implements IdentityAccounts {
    @PersistenceContext private EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public AccountStore(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction = new TransactionTemplate(manager);
        readTransaction.setReadOnly(true);
    }

    public UUID login(String issuer, String subject, String name, String email, boolean emailVerified) {
        try {
            return transact(transaction, () -> {
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
            return transact(transaction, () -> {
                var winner = identity(issuer, subject);
                if (winner == null) throw failure;
                return winner.userId;
            });
        }
    }

    private <T> T transact(TransactionTemplate template, Supplier<T> work) {
        class ApplicationFailure { RuntimeException cause; }
        var application = new ApplicationFailure();
        try {
            return template.execute(status -> {
                try {
                    return work.get();
                } catch (RuntimeException failure) {
                    application.cause = failure;
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            if (application.cause != null && application.cause != failure) {
                application.cause.addSuppressed(failure);
                throw application.cause;
            }
            throw failure;
        }
    }

    public AccountProfile profile(UUID id) {
        return transact(readTransaction, () -> {
            var user = user(id);
            if (user == null) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
            return new AccountProfile(user.id, user.fullName, user.role.name(), user.phoneNumber, verified(user),
                    user.averageRating, user.reviewCount, user.brokerReportCount,
                    TrustBadge.calculate(user.averageRating, user.reviewCount, user.brokerReportCount));
        });
    }

    @Override
    public boolean hasVerifiedContact(UUID userId) {
        return transact(readTransaction, () -> {
            var user = user(userId);
            return user != null && verified(user);
        });
    }

    private boolean verified(UserEntity user) {
        return user.phoneVerifiedAt != null && user.phoneNumber != null && user.phoneNumber.matches("\\+91[0-9]{10}");
    }

    private UserEntity user(UUID id) {
        return entityManager.createQuery("select u from UserEntity u where u.id = :id", UserEntity.class)
                .setParameter("id", id).getSingleResultOrNull();
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

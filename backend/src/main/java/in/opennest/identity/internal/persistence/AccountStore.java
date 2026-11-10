package in.opennest.identity.internal.persistence;

import java.util.UUID;
import java.util.function.Supplier;
import in.opennest.common.RequestFailure;
import in.opennest.identity.TrustBadge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
public class AccountStore {
    @PersistenceContext private EntityManager entityManager;
    private final TransactionTemplate readTransaction;

    public AccountStore(PlatformTransactionManager manager) {
        readTransaction = new TransactionTemplate(manager);
        readTransaction.setReadOnly(true);
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

}

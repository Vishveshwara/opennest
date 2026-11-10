package in.opennest.identity.internal.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import in.opennest.common.RequestFailure;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountStoreTransactionTests {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final AccountStore accounts = new AccountStore(manager);

    @BeforeEach
    void configureTransaction() {
        ReflectionTestUtils.setField(accounts, "entityManager", entityManager);
        when(manager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void preservesOriginalReadFailureWhenRollbackSucceeds() {
        var outage = new PersistenceException(new SQLException("fabricated outage", "08006"));
        when(entityManager.createQuery(anyString(), eq(UserEntity.class))).thenThrow(outage);

        assertThat(catchThrowable(() -> accounts.profile(UUID.randomUUID()))).isSameAs(outage);
        assertThat(outage.getSuppressed()).isEmpty();
        verify(manager).rollback(status);
    }

    @Test
    void preservesUnexpectedReadFailureWhenRollbackFails() {
        var failure = new IllegalStateException("fabricated application failure");
        var rollback = rollbackFailure();
        when(entityManager.createQuery(anyString(), eq(UserEntity.class))).thenThrow(failure);
        doThrow(rollback).when(manager).rollback(status);

        assertThat(catchThrowable(() -> accounts.profile(UUID.randomUUID()))).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(rollback);
    }

    @Test
    void preservesReadTransactionStartFailure() {
        var failure = new CannotCreateTransactionException("fabricated start failure");
        when(manager.getTransaction(any())).thenThrow(failure);

        assertThat(catchThrowable(() -> accounts.profile(UUID.randomUUID()))).isSameAs(failure);
        verify(manager, never()).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test
    void preservesCommitFailureAfterSuccessfulContactLookup() {
        var user = verifiedUser();
        var failure = rollbackFailure();
        doThrow(failure).when(manager).commit(status);

        assertThat(catchThrowable(() -> accounts.hasVerifiedContact(user.id))).isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
        verify(manager).commit(status);
    }

    @Test
    void returnsContactEligibilityOnlyAfterCommit() {
        var user = verifiedUser();

        assertThat(accounts.hasVerifiedContact(user.id)).isTrue();
        verify(manager).commit(status);
        verify(manager, never()).rollback(any());
        verify(entityManager, never()).persist(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "contact"})
    void preservesReadOutageWhenRollbackAlsoFails(String operation) {
        var outage = new PersistenceException(new SQLException("fabricated outage", "57P01"));
        var rollback = rollbackFailure();
        when(entityManager.createQuery(anyString(), eq(UserEntity.class))).thenThrow(outage);
        doThrow(rollback).when(manager).rollback(status);
        var factory = new ProxyFactory(accounts);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var transactionalAccounts = (AccountStore) factory.getProxy();

        assertThat(catchThrowable(() -> {
            if (operation.equals("profile")) transactionalAccounts.profile(UUID.randomUUID());
            else transactionalAccounts.hasVerifiedContact(UUID.randomUUID());
        })).isSameAs(outage);
        assertThat(outage.getSuppressed()).containsExactly(rollback);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingProfileRetainsRollbackFailureWithoutStackTrace() {
        TypedQuery<UserEntity> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(UserEntity.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResultOrNull()).thenReturn(null);
        var rollback = rollbackFailure();
        doThrow(rollback).when(manager).rollback(status);

        assertThat(catchThrowable(() -> accounts.profile(UUID.randomUUID())))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.UNAUTHENTICATED);
                    assertThat(failure.getSuppressed()).containsExactly(rollback);
                    assertThat(failure.getStackTrace()).isEmpty();
                    assertThat(failure.fillInStackTrace().getStackTrace()).isEmpty();
                });
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    private JpaSystemException rollbackFailure() {
        return new JpaSystemException(new TransactionException(
                "fabricated rollback failure", new SQLException("fabricated closed connection")));
    }

    @SuppressWarnings("unchecked")
    private UserEntity verifiedUser() {
        TypedQuery<UserEntity> query = mock(TypedQuery.class);
        var user = new UserEntity();
        user.phoneNumber = "+910000000001";
        user.phoneVerifiedAt = Instant.now();
        when(entityManager.createQuery(anyString(), eq(UserEntity.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResultOrNull()).thenReturn(user);
        return user;
    }
}

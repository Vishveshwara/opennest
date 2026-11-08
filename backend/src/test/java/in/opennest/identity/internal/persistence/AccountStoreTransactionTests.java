package in.opennest.identity.internal.persistence;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

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
    void preservesDatabaseOutageWhenRollbackAlsoFails() {
        var outage = new PersistenceException(new SQLException("fabricated outage", "57P01"));
        var rollback = rollbackFailure();
        when(entityManager.createQuery(anyString(), eq(AuthIdentityEntity.class))).thenThrow(outage);
        doThrow(rollback).when(manager).rollback(status);

        assertThat(catchThrowable(this::login)).isSameAs(outage);
        assertThat(outage.getSuppressed()).containsExactly(rollback);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void preservesOriginalFailureWhenRollbackSucceeds() {
        var outage = new PersistenceException(new SQLException("fabricated outage", "08006"));
        when(entityManager.createQuery(anyString(), eq(AuthIdentityEntity.class))).thenThrow(outage);

        assertThat(catchThrowable(this::login)).isSameAs(outage);
        assertThat(outage.getSuppressed()).isEmpty();
        verify(manager).rollback(status);
    }

    @Test
    void preservesUnexpectedApplicationFailureWhenRollbackFails() {
        var failure = new IllegalStateException("fabricated application failure");
        var rollback = rollbackFailure();
        when(entityManager.createQuery(anyString(), eq(AuthIdentityEntity.class))).thenThrow(failure);
        doThrow(rollback).when(manager).rollback(status);

        assertThat(catchThrowable(this::login)).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(rollback);
    }

    @Test
    void preservesTransactionStartFailure() {
        var failure = new CannotCreateTransactionException("fabricated start failure");
        when(manager.getTransaction(any())).thenThrow(failure);

        assertThat(catchThrowable(this::login)).isSameAs(failure);
        verify(manager, never()).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test
    void preservesCommitFailureAfterSuccessfulAccountLookup() {
        existingIdentity();
        var failure = rollbackFailure();
        doThrow(failure).when(manager).commit(status);

        assertThat(catchThrowable(this::login)).isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
        verify(manager).commit(status);
    }

    @Test
    void returnsExistingAccountOnlyAfterCommit() {
        var identity = existingIdentity();

        assertThat(login()).isEqualTo(identity.userId);
        verify(manager).commit(status);
        verify(manager, never()).rollback(any());
        verify(entityManager, never()).persist(any());
    }

    @Test
    void preservesOutageDuringConcurrentSignupWinnerLookup() {
        var conflict = new org.hibernate.exception.ConstraintViolationException(
                "fabricated conflict", new SQLException("fabricated unique violation", "23505"),
                "uq_auth_identities_issuer_subject");
        var outage = new PersistenceException(new SQLException("fabricated outage", "57P01"));
        var winnerStatus = new SimpleTransactionStatus();
        var rollback = rollbackFailure();
        when(manager.getTransaction(any())).thenReturn(status, winnerStatus);
        when(entityManager.createQuery(anyString(), eq(AuthIdentityEntity.class))).thenThrow(conflict, outage);
        doThrow(rollback).when(manager).rollback(winnerStatus);

        assertThat(catchThrowable(this::login)).isSameAs(outage);
        assertThat(outage.getSuppressed()).containsExactly(rollback);
        verify(manager).rollback(status);
        verify(manager).rollback(winnerStatus);
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "contact"})
    void preservesReadOutageWhenRollbackAlsoFails(String operation) {
        var outage = new PersistenceException(new SQLException("fabricated outage", "57P01"));
        var rollback = rollbackFailure();
        when(entityManager.find(eq(UserEntity.class), any())).thenThrow(outage);
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

    private UUID login() {
        return accounts.login("https://accounts.google.com", "fabricated-subject", "OpenNest test", null, false);
    }

    private JpaSystemException rollbackFailure() {
        return new JpaSystemException(new TransactionException(
                "fabricated rollback failure", new SQLException("fabricated closed connection")));
    }

    @SuppressWarnings("unchecked")
    private AuthIdentityEntity existingIdentity() {
        TypedQuery<AuthIdentityEntity> query = mock(TypedQuery.class);
        var identity = new AuthIdentityEntity();
        identity.userId = UUID.randomUUID();
        when(entityManager.createQuery(anyString(), eq(AuthIdentityEntity.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultStream()).thenReturn(Stream.of(identity));
        return identity;
    }
}

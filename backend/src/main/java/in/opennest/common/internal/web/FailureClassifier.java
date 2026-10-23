package in.opennest.common.internal.web;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;

import in.opennest.common.RequestFailure;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.ServletException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;
import org.hibernate.JDBCException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;

final class FailureClassifier {

    private FailureClassifier() {
    }

    static int status(Exception failure) {
        if (failure instanceof RequestFailure request) {
            return switch (request.kind()) {
                case INVALID_INPUT -> 400;
                case UNAUTHENTICATED -> 401;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case RATE_LIMITED -> 429;
                case UNAVAILABLE -> 503;
            };
        }
        if (failure instanceof AccessDeniedException) return 403;
        if (failure instanceof AuthenticationException) return 401;
        if (failure instanceof RequestRejectedException) return 400;
        if (failure instanceof ConversionNotSupportedException) return 500;
        if (failure instanceof HttpMessageNotReadableException || failure instanceof TypeMismatchException
                || failure instanceof BindException || failure instanceof ConstraintViolationException) return 400;
        if (failure instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            return switch (status) {
                case 400, 401, 403, 404, 405, 406, 409, 413, 415, 429, 503 -> status;
                default -> 500;
            };
        }
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof DuplicateKeyException || current instanceof OptimisticLockingFailureException
                    || current instanceof OptimisticLockException) return 409;
            if (current instanceof DataAccessResourceFailureException || current instanceof QueryTimeoutException
                    || current instanceof TransientDataAccessResourceException || current instanceof SQLTimeoutException
                    || current instanceof SQLTransientConnectionException || current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException) return 503;
            if (current instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState())) return 409;
                if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return 503;
                return 500;
            }
            if (current instanceof DataAccessException || current instanceof PersistenceException
                    || current instanceof JDBCException || current instanceof CannotCreateTransactionException
                    || current instanceof TransactionSystemException || current instanceof ServletException) {
                current = current.getCause();
            } else {
                return 500;
            }
        }
        return 500;
    }
}

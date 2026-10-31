package in.opennest.common.internal.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.util.Set;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ApiExceptionHandler {

    private final ProblemResponses responses;

    ApiExceptionHandler(ProblemResponses responses) {
        this.responses = responses;
    }

    @ExceptionHandler(Exception.class)
    void handle(Exception failure, HttpServletResponse response) throws IOException {
        if (failure instanceof AccessDeniedException denied) throw denied;
        if (failure instanceof AuthenticationException authentication) throw authentication;
        if (failure instanceof HttpRequestMethodNotSupportedException methodFailure) {
            var allowed = methodFailure.getSupportedHttpMethods();
            responses.write(response, FailureClassifier.status(failure), allowed == null ? Set.of() : allowed);
        } else {
            responses.write(response, FailureClassifier.status(failure));
        }
    }
}

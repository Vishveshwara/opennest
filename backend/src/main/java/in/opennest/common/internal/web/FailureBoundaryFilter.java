package in.opennest.common.internal.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

final class FailureBoundaryFilter extends OncePerRequestFilter {

    private final ProblemResponses responses;

    FailureBoundaryFilter(ProblemResponses responses) {
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception failure) {
            try {
                responses.write(response, FailureClassifier.status(failure));
            } catch (IOException disconnectedClient) {
                return;
            }
        }
    }
}

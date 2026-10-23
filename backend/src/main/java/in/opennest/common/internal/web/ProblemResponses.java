package in.opennest.common.internal.web;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ProblemResponses {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemResponses.class);
    private final JsonMapper mapper;

    public ProblemResponses(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, int status) throws IOException {
        write(response, status, Set.of());
    }

    void write(HttpServletResponse response, int status, Set<HttpMethod> allowedMethods) throws IOException {
        UUID errorId = UUID.randomUUID();
        LOG.warn("OpenNest HTTP error status={} errorId={}", status, errorId);
        if (response.isCommitted()) return;
        response.reset();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Error-Id", errorId.toString());
        if (!allowedMethods.isEmpty()) {
            response.setHeader("Allow", String.join(", ", allowedMethods.stream().map(HttpMethod::name).sorted().toList()));
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail(status));
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setInstance(URI.create("urn:uuid:" + errorId));
        mapper.writeValue(response.getOutputStream(), problem);
    }

    private String detail(int status) {
        return switch (status) {
            case 400 -> "The request is invalid.";
            case 401 -> "Authentication is required.";
            case 403 -> "Access is denied.";
            case 404 -> "The requested resource was not found.";
            case 405 -> "The request method is not allowed.";
            case 406 -> "The requested response format is not available.";
            case 409 -> "The request conflicts with the current state.";
            case 413 -> "The request is too large.";
            case 415 -> "The request media type is not supported.";
            case 429 -> "The request limit has been reached.";
            case 503 -> "The service is temporarily unavailable.";
            default -> "An unexpected error occurred.";
        };
    }
}

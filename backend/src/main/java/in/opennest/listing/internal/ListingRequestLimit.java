package in.opennest.listing.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import in.opennest.common.HttpFailureResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-90)
class ListingRequestLimit extends OncePerRequestFilter {
    private final HttpFailureResponse errors;
    private final JsonMapper strict = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    ListingRequestLimit(HttpFailureResponse errors) { this.errors = errors; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!request.getRequestURI().startsWith("/api/v1/listings") || !java.util.Set.of("POST", "PATCH").contains(request.getMethod())) {
            chain.doFilter(request, response); return;
        }
        if (request.getContentLengthLong() > 32768) { errors.write(response, 413); return; }
        var body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) { errors.write(response, 413); return; }
        try (var parser = strict.createParser(body)) { strict.readTree(parser); if (parser.nextToken() != null) throw new IllegalArgumentException(); }
        catch (RuntimeException failure) { errors.write(response, 400); return; }
        var wrapped = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        chain.doFilter(wrapped, response);
    }
}

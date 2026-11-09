package in.opennest.listing.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import in.opennest.common.HttpFailureResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-90)
class ListingRequestLimit extends OncePerRequestFilter {
    private final HttpFailureResponse errors;
    private final CorsConfigurationSource cors;
    private final DefaultCorsProcessor processor;
    private final JsonMapper strict = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    ListingRequestLimit(HttpFailureResponse errors, @org.springframework.beans.factory.annotation.Qualifier("applicationCors") CorsConfigurationSource cors) {
        this.errors = errors; this.cors = cors;
        processor = new DefaultCorsProcessor() {
            @Override protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response) throws IOException {
                errors.write(((org.springframework.http.server.ServletServerHttpResponse) response).getServletResponse(), 403);
            }
        };
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String path = new UrlPathHelper().getPathWithinApplication(request);
        if (!path.equals("/api/v1/listings") && !path.startsWith("/api/v1/listings/")) {
            chain.doFilter(request, response); return;
        }
        if (!processor.processRequest(cors.getCorsConfiguration(request), request, response) || CorsUtils.isPreFlightRequest(request)) return;
        if (!java.util.Set.of("POST", "PATCH").contains(request.getMethod())) { chain.doFilter(request, response); return; }
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

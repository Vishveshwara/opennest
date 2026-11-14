package in.opennest.listing.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FrontendStorageFixture {
    @Bean(destroyMethod = "close") @Primary
    public BrowserStorage frontendBrowserStorage(org.springframework.core.env.Environment environment) throws IOException { return new BrowserStorage(Set.of(environment.getProperty("OPENNEST_FRONTEND_ORIGIN", "http://localhost:3000"), environment.getProperty("SANDBOX_FRONTEND_ORIGIN", "http://localhost:3001"))); }

    public static final class BrowserStorage implements StorageObjects, AutoCloseable {
        private record Reservation(String key, String type, long size, Instant expires) {}
        private record Download(String key, Instant expires) {}
        private record ObjectBytes(byte[] bytes, String type, Instant modified) {}
        private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
        private final Map<String, ObjectBytes> staging = new ConcurrentHashMap<>();
        private final Map<String, ObjectBytes> finals = new ConcurrentHashMap<>();
        private final Map<String, Download> downloads = new ConcurrentHashMap<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpServer server;
        private final Set<String> allowedOrigins;

        BrowserStorage(Set<String> allowedOrigins) throws IOException {
            this.allowedOrigins = allowedOrigins;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/storage/", this::serve);
            server.start();
        }

        public HttpServer server() { return server; }
        public String origin() { return "http://localhost:" + server.getAddress().getPort(); }
        @Override public boolean enabled() { return true; }

        @Override public Upload upload(String key, String type, long bytes) {
            String token = UUID.randomUUID().toString();
            reservations.put(token, new Reservation(key, type, bytes, Instant.now().plusSeconds(600)));
            return new Upload(origin() + "/storage/staging/" + token, Map.of("Content-Type", type));
        }

        @Override public byte[] read(String key, long expectedSize) {
            var object = staging.get(key);
            ListingInput.require(object != null && object.bytes().length == expectedSize);
            return object.bytes().clone();
        }

        @Override public void writeFinal(String key, byte[] bytes, String type) {
            if (finals.putIfAbsent(key, new ObjectBytes(bytes.clone(), type, Instant.now())) != null)
                throw new IllegalStateException("Final browser fixture object is immutable");
        }

        @Override public String download(String key) {
            String token = UUID.randomUUID().toString();
            downloads.put(token, new Download(key, Instant.now().plusSeconds(300)));
            return origin() + "/storage/final/" + token;
        }

        @Override public void delete(Bucket bucket, String key) { objects(bucket).remove(key); }

        @Override public void visit(Bucket bucket, Consumer<Stored> visitor) {
            List.copyOf(objects(bucket).entrySet()).forEach(entry -> visitor.accept(new Stored(entry.getKey(), entry.getValue().modified())));
        }

        private Map<String, ObjectBytes> objects(Bucket bucket) { return bucket == Bucket.STAGING ? staging : finals; }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
                if (requestOrigin != null) {
                    if (!allowedOrigins.contains(requestOrigin)) {
                        reply(exchange, 403, "text/plain", new byte[0]); return;
                    }
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", requestOrigin);
                    exchange.getResponseHeaders().set("Vary", "Origin");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,HEAD,PUT,OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                }
                if (exchange.getRequestMethod().equals("OPTIONS")) { reply(exchange, 204, "text/plain", new byte[0]); return; }
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/storage/staging/")) {
                    var reservation = reservations.get(path.substring("/storage/staging/".length()));
                    if (reservation == null || !reservation.expires().isAfter(Instant.now())) {
                        reply(exchange, 403, "text/plain", new byte[0]); return;
                    }
                    if (exchange.getRequestMethod().equals("PUT")) {
                        var bytes = exchange.getRequestBody().readNBytes(ListingMedia.MAX_BYTES + 1);
                        if (bytes.length != reservation.size() || !reservation.type().equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                            reply(exchange, 400, "text/plain", new byte[0]); return;
                        }
                        staging.put(reservation.key(), new ObjectBytes(bytes, reservation.type(), Instant.now()));
                        reply(exchange, 200, "text/plain", new byte[0]); return;
                    }
                    var object = staging.get(reservation.key());
                    if (object != null && Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
                        reply(exchange, 200, object.type(), object.bytes()); return;
                    }
                }
                if (path.startsWith("/storage/final/") && Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
                    var download = downloads.get(path.substring("/storage/final/".length()));
                    var object = download == null || !download.expires().isAfter(Instant.now()) ? null : finals.get(download.key());
                    if (object != null) { reply(exchange, 200, object.type(), object.bytes()); return; }
                }
                reply(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        }

        private void reply(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            boolean noBody = status == 204 || exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(status, noBody ? -1 : bytes.length);
            if (!noBody) exchange.getResponseBody().write(bytes);
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}

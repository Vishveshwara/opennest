package in.opennest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

final class ControlledOidcProvider implements AutoCloseable {
    static final String SECRET = "fabricated-oidc-provider-secret";
    static final String EMAIL = "fabricated-account@example.invalid";
    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final JsonMapper json = new JsonMapper();
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    final AtomicInteger exchanges = new AtomicInteger();

    ControlledOidcProvider() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/jwks", exchange -> send(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
        server.start();
    }

    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void authorize(HttpExchange exchange) throws IOException {
        var query = parameters(exchange.getRequestURI().getRawQuery());
        String state = query.get("state");
        String callback = query.get("redirect_uri");
        if (!"code".equals(query.get("response_type")) || !"S256".equals(query.get("code_challenge_method"))
                || query.get("code_challenge") == null || query.get("nonce") == null || state == null) {
            send(exchange, 400, "{}");
            return;
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, query);
        String suffix = "denied".equals(query.get("scenario")) ? "error=access_denied" : "code=" + code;
        exchange.getResponseHeaders().add("Location", callback + "?" + suffix + "&state=" + encode(state));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        exchanges.incrementAndGet();
        var body = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var authorization = codes.remove(body.get("code"));
        try {
            if (authorization == null) throw new IllegalArgumentException();
            String scenario = authorization.getOrDefault("scenario", "valid");
            String basic = Base64.getEncoder().encodeToString((authorization.get("client_id") + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
            String verifier = body.getOrDefault("code_verifier", "");
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            if (!exchange.getRequestHeaders().getFirst("Authorization").equals("Basic " + basic)
                    || !authorization.get("redirect_uri").equals(body.get("redirect_uri"))
                    || !"authorization_code".equals(body.get("grant_type"))
                    || !authorization.get("code_challenge").equals(challenge)
                    || scenario.equals("pkce") || scenario.equals("expired-code")) throw new IllegalArgumentException();
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(scenario.equals("issuer") ? "https://wrong.example.invalid" : origin())
                    .subject(authorization.getOrDefault("subject", "fabricated-subject"))
                    .audience(scenario.equals("audience") ? "wrong-client" : authorization.get("client_id"))
                    .issueTime(Date.from(now.minusSeconds(600)))
                    .expirationTime(Date.from(now.plusSeconds(scenario.equals("expired-token") ? -300 : 300)))
                    .claim("name", "Fabricated OpenNest user").claim("email", EMAIL).claim("email_verified", true);
            if (!scenario.equals("missing-nonce")) claims.claim("nonce", scenario.equals("nonce") ? "wrong-nonce" : authorization.get("nonce"));
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test-key").build(), claims.build());
            jwt.sign(new RSASSASigner(scenario.equals("signature") ? wrongKey : signingKey));
            send(exchange, 200, json.writeValueAsString(Map.of("access_token", "fabricated-access-token-canary",
                    "token_type", "Bearer", "expires_in", 300, "id_token", jwt.serialize())));
        } catch (Exception failure) {
            send(exchange, 400, "{\"error\":\"invalid_grant\"}");
        }
    }

    static Map<String, String> parameters(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String entry : query.split("&")) {
            var pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    @Override public void close() { server.stop(0); }
}

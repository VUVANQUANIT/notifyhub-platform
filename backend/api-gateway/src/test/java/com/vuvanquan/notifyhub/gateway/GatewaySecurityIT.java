package com.vuvanquan.notifyhub.gateway;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIT {
    static final KeyPair KEY = pair();
    static final HttpServer UPSTREAM = upstream();
    static String upstreamUri() { return "http://127.0.0.1:" + UPSTREAM.getAddress().getPort(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GatewaySecurityIT::upstreamUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> upstreamUri() + "/.well-known/jwks.json");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "test-upstream");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", GatewaySecurityIT::upstreamUri);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/**,/.well-known/jwks.json");
    }
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    @Test void anonymous_headers_cannot_access_guarded_routes_but_jwks_login_are_public() throws Exception {
        assertThat(request("GET", "/api/campaigns", null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/reports/summary", null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/.well-known/jwks.json", null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/auth/login", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
    }
    @Test void real_signed_jwt_passes_read_scope_and_write_scope_requires_operator() throws Exception {
        var viewer = token(KEY, upstreamUri(), "notifyhub", "campaigns:read reports:read", Instant.now().plusSeconds(120));
        assertThat(request("GET", "/api/campaigns", viewer).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/reports/summary", viewer).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/campaigns", viewer).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/campaigns", token(KEY, upstreamUri(), "notifyhub", "campaigns:write", Instant.now().plusSeconds(120))).statusCode()).isEqualTo(200);
    }
    @Test void wrong_signature_issuer_audience_and_expiry_are_rejected() throws Exception {
        for (String invalid : List.of(token(pair(), upstreamUri(), "notifyhub", "campaigns:read", Instant.now().plusSeconds(120)),
                token(KEY, "https://attacker.test", "notifyhub", "campaigns:read", Instant.now().plusSeconds(120)),
                token(KEY, upstreamUri(), "other-app", "campaigns:read", Instant.now().plusSeconds(120)),
                token(KEY, upstreamUri(), "notifyhub", "campaigns:read", Instant.now().minusSeconds(120)))) {
            assertThat(request("GET", "/api/campaigns", invalid).statusCode()).isEqualTo(401);
        }
    }
    @Test void authenticated_members_are_guarded_and_no_session_cookie_is_created() throws Exception {
        var viewer = token(KEY, upstreamUri(), "notifyhub", "campaigns:read", Instant.now().plusSeconds(120));
        assertThat(request("POST", "/api/auth/users", viewer).statusCode()).isEqualTo(403);
        var admin = token(KEY, upstreamUri(), "notifyhub", "users:read users:write", Instant.now().plusSeconds(120));
        assertThat(request("POST", "/api/auth/users", admin).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/auth/users/123/role", admin).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/me", viewer).headers().allValues("Set-Cookie")).isEmpty();
    }
    @Test void recovery_credentials_are_public_json_routes_without_opening_member_administration() throws Exception {
        assertThat(request("POST", "/api/auth/password/forgot", null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/auth/password/reset", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/users", null).statusCode()).isEqualTo(401);
    }
    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Tenant-Id", UUID.randomUUID().toString());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.header("Content-Type", "application/json").method(method,
                method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(KeyPair key, String issuer, String audience, String scope, Instant expiry) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("gateway-test").build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject(UUID.randomUUID().toString()).audience(audience)
                        .claim("tenant_id", UUID.randomUUID().toString()).claim("scope", scope)
                        .expirationTime(Date.from(expiry)).issueTime(Date.from(expiry.minusSeconds(600))).build());
        jwt.sign(new RSASSASigner(key.getPrivate())); return jwt.serialize();
    }
    private static KeyPair pair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String body = exchange.getRequestURI().getPath().equals("/.well-known/jwks.json")
                        ? new JWKSet(new RSAKey.Builder((RSAPublicKey) KEY.getPublic()).keyID("gateway-test").build()).toString() : "{\"ok\":true}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @AfterAll static void stop() { UPSTREAM.stop(0); }
}

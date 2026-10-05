package com.vuvanquan.notifyhub.auth;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jwt.SignedJWT;
import com.vuvanquan.notifyhub.auth.application.AuthFailure;
import com.vuvanquan.notifyhub.auth.application.AuthModels.*;
import com.vuvanquan.notifyhub.auth.application.IdentityService;
import com.vuvanquan.notifyhub.auth.configuration.SigningKeys;
import com.vuvanquan.notifyhub.auth.domain.Role;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    static final String KEY_PATH = keyPath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("auth.signing-key-path", () -> KEY_PATH);
    }
    @LocalServerPort int port;
    @Autowired IdentityService service;
    @Autowired JdbcTemplate db;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired SigningKeys keys;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach void reset() {
        db.execute("DROP TRIGGER IF EXISTS reject_refresh ON auth.refresh_tokens");
        db.execute("TRUNCATE auth.refresh_tokens,auth.refresh_sessions,auth.users,auth.tenants CASCADE");
    }

    @Test void register_login_issue_verifiable_jwt_and_never_store_or_return_secrets_in_user_views() throws Exception {
        Tokens tokens = register("tenant-one");
        var jwt = decoder.decode(tokens.accessToken());
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://127.0.0.1:8081");
        assertThat(jwt.getAudience()).containsExactly("notifyhub");
        assertThat(jwt.getClaimAsString("scope")).contains("campaigns:write", "reports:read", "users:write");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(301));
        assertThat(db.queryForObject("SELECT password_hash FROM auth.users", String.class)).startsWith("{pbkdf2-sha256}").doesNotContain(PASSWORD);
        assertThat(db.queryForObject("SELECT token_hash FROM auth.refresh_tokens", String.class)).hasSize(64).isNotEqualTo(tokens.refreshToken());
        var me = request("GET", "/api/auth/me", null, tokens.accessToken());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).doesNotContain("password", "refreshToken", tokens.refreshToken());
        var login = request("POST", "/api/auth/login", Map.of("tenantSlug", "TENANT-ONE", "email", " ADMIN@example.com ", "password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(body(login).get("refreshToken").asText()).isNotEqualTo(tokens.refreshToken());
    }

    @Test void jwks_is_public_and_contains_only_public_rsa_parameters() throws Exception {
        var response = request("GET", "/.well-known/jwks.json", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var jwk = body(response).get("keys").get(0);
        assertThat(jwk.get("kty").asText()).isEqualTo("RSA");
        assertThat(jwk.get("alg").asText()).isEqualTo("RS256");
        for (String privateField : List.of("d", "p", "q", "dp", "dq", "qi")) assertThat(jwk.has(privateField)).isFalse();
        var remote = NimbusJwtDecoder.withJwkSetUri("http://127.0.0.1:" + port + "/.well-known/jwks.json").build();
        assertThat(remote.decode(register("verify-jwks").accessToken()).getSubject()).isNotBlank();
    }

    @Test void invalid_credentials_are_generic_and_registration_validation_is_bounded() throws Exception {
        register("tenant-one");
        String detail = null;
        for (var attempt : List.of(Map.of("tenantSlug", "tenant-one", "email", "admin@example.com", "password", "wrong"),
                Map.of("tenantSlug", "missing-tenant", "email", "admin@example.com", "password", PASSWORD),
                Map.of("tenantSlug", "tenant-one", "email", "missing@example.com", "password", PASSWORD))) {
            var response = request("POST", "/api/auth/login", attempt, null);
            assertThat(response.statusCode()).isEqualTo(401);
            if (detail == null) detail = body(response).get("detail").asText();
            else assertThat(body(response).get("detail").asText()).isEqualTo(detail);
        }
        assertThat(request("POST", "/api/auth/register", registration("tenant-one"), null).statusCode()).isEqualTo(409);
        var invalid = new HashMap<>(registration("bad/slug")); invalid.put("password", "short");
        assertThat(request("POST", "/api/auth/register", invalid, null).statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM auth.tenants", Integer.class)).isEqualTo(1);
    }

    @Test void anonymous_forged_headers_and_viewer_cannot_manage_members() throws Exception {
        var admin = register("tenant-one"); var actor = actor(admin);
        service.createUser(actor, "viewer@example.com", "Viewer", PASSWORD, Role.VIEWER);
        var viewer = service.login("tenant-one", "viewer@example.com", PASSWORD);
        assertThat(request("GET", "/api/auth/users", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/auth/users", null, viewer.accessToken()).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/auth/users", Map.of("email", "another@example.com", "displayName", "Another", "password", PASSWORD, "role", "VIEWER"), viewer.accessToken()).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/auth/me", null, viewer.accessToken()).statusCode()).isEqualTo(200);
        assertThat(decoder.decode(viewer.accessToken()).getClaimAsString("scope")).doesNotContain("write");
    }

    @Test void tenant_isolation_and_member_mutations_work_through_bearer_api_without_csrf_cookie() throws Exception {
        var one = register("tenant-one"); var two = register("tenant-two");
        assertThat(actor(one).tenantId()).isNotEqualTo(actor(two).tenantId());
        assertThat(request("PUT", "/api/auth/users/" + actor(two).userId() + "/role", Map.of("role", "VIEWER"), one.accessToken()).statusCode()).isEqualTo(404);
        var created = request("POST", "/api/auth/users", Map.of("email", "operator@example.com", "displayName", "Operator", "password", PASSWORD, "role", "OPERATOR"), one.accessToken());
        assertThat(created.statusCode()).isEqualTo(201);
        String member = body(created).get("id").asText();
        assertThat(request("PUT", "/api/auth/users/" + member + "/role", Map.of("role", "VIEWER"), one.accessToken()).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/auth/users/" + member + "/enabled", Map.of("enabled", false), one.accessToken()).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/users", null, two.accessToken()).body()).doesNotContain("operator@example.com");
    }

    @Test void cannot_remove_last_enabled_admin_and_stale_admin_token_loses_identity_privileges() throws Exception {
        var one = register("tenant-one"); var actor = actor(one);
        assertThat(request("PUT", "/api/auth/users/" + actor.userId() + "/role", Map.of("role", "VIEWER"), one.accessToken()).statusCode()).isEqualTo(409);
        assertThat(request("PUT", "/api/auth/users/" + actor.userId() + "/enabled", Map.of("enabled", false), one.accessToken()).statusCode()).isEqualTo(409);
        var second = service.createUser(actor, "second@example.com", "Second", PASSWORD, Role.ADMIN);
        var secondTokens = service.login("tenant-one", "second@example.com", PASSWORD);
        service.changeRole(actor, second.id(), Role.VIEWER);
        assertThat(request("GET", "/api/auth/users", null, secondTokens.accessToken()).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> service.refresh(secondTokens.refreshToken())).isInstanceOf(AuthFailure.class);
    }

    @Test void concurrent_admin_demotions_keep_at_least_one_enabled_admin() throws Exception {
        var one = register("tenant-one"); var a1 = actor(one);
        service.createUser(a1, "second@example.com", "Second", PASSWORD, Role.ADMIN);
        var a2 = actor(service.login("tenant-one", "second@example.com", PASSWORD));
        try (var threads = Executors.newFixedThreadPool(2)) {
            var results = threads.invokeAll(List.of(() -> demote(a1), () -> demote(a2)));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM auth.users WHERE enabled AND role='ADMIN'", Integer.class)).isEqualTo(1);
    }

    @Test void refresh_rotation_replay_revokes_entire_session_and_logout_is_idempotent() throws Exception {
        var initial = register("tenant-one");
        var independent = service.login("tenant-one", "admin@example.com", PASSWORD);
        var rotated = service.refresh(initial.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.refreshExpiresAt()).isEqualTo(initial.refreshExpiresAt());
        assertThatThrownBy(() -> service.refresh(initial.refreshToken())).isInstanceOf(AuthFailure.class);
        assertThatThrownBy(() -> service.refresh(rotated.refreshToken())).isInstanceOf(AuthFailure.class);
        var alive = service.refresh(independent.refreshToken());
        assertThat(request("POST", "/api/auth/logout", Map.of("refreshToken", alive.refreshToken()), null).statusCode()).isEqualTo(204);
        service.logout(alive.refreshToken());
        assertThatThrownBy(() -> service.refresh(alive.refreshToken())).isInstanceOf(AuthFailure.class);
    }

    @Test void concurrent_refresh_does_not_create_two_live_successors() throws Exception {
        var initial = register("tenant-one");
        try (var threads = Executors.newFixedThreadPool(2)) {
            Callable<Tokens> refresh = () -> { try { return service.refresh(initial.refreshToken()); } catch (AuthFailure failure) { return null; } };
            var results = threads.invokeAll(List.of(refresh, refresh));
            var tokens = results.stream().map(result -> { try { return result.get(); } catch (Exception failure) { throw new RuntimeException(failure); } }).filter(Objects::nonNull).toList();
            assertThat(tokens).hasSize(1);
            assertThatThrownBy(() -> service.refresh(tokens.getFirst().refreshToken())).isInstanceOf(AuthFailure.class);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test void failed_refresh_persistence_rolls_back_consumption_so_original_can_retry() {
        var initial = register("tenant-one");
        db.execute("CREATE OR REPLACE FUNCTION auth.reject_refresh() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected insert failure'; END; $$ LANGUAGE plpgsql");
        db.execute("CREATE TRIGGER reject_refresh BEFORE INSERT ON auth.refresh_tokens FOR EACH ROW EXECUTE FUNCTION auth.reject_refresh()");
        assertThatThrownBy(() -> service.refresh(initial.refreshToken())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM auth.refresh_tokens WHERE consumed_at IS NOT NULL", Integer.class)).isZero();
        db.execute("DROP TRIGGER reject_refresh ON auth.refresh_tokens");
        assertThat(service.refresh(initial.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test void expired_sessions_disabled_users_and_disabled_tenants_cannot_refresh_or_login() throws Exception {
        var admin = register("tenant-one"); var actor = actor(admin);
        var member = service.createUser(actor, "viewer@example.com", "Viewer", PASSWORD, Role.VIEWER);
        var viewer = service.login("tenant-one", "viewer@example.com", PASSWORD);
        service.changeEnabled(actor, member.id(), false);
        assertThatThrownBy(() -> service.login("tenant-one", "viewer@example.com", PASSWORD)).isInstanceOf(AuthFailure.class);
        assertThat(request("GET", "/api/auth/me", null, viewer.accessToken()).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> service.refresh(viewer.refreshToken())).isInstanceOf(AuthFailure.class);
        db.update("UPDATE auth.refresh_sessions SET expires_at=now()-interval '1 second'");
        assertThatThrownBy(() -> service.refresh(admin.refreshToken())).isInstanceOf(AuthFailure.class);
        db.update("UPDATE auth.tenants SET enabled=false");
        assertThatThrownBy(() -> service.login("tenant-one", "admin@example.com", PASSWORD)).isInstanceOf(AuthFailure.class);
    }

    @Test void rejects_expired_wrong_audience_wrong_issuer_and_forged_signature() throws Exception {
        var initial = register("tenant-one"); var original = decoder.decode(initial.accessToken());
        for (var claims : List.of(JwtClaimsSet.builder().claims(c -> c.putAll(original.getClaims())).issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(120)).build(),
                JwtClaimsSet.builder().claims(c -> c.putAll(original.getClaims())).audience(List.of("other-app")).build(),
                JwtClaimsSet.builder().claims(c -> c.putAll(original.getClaims())).issuer("https://attacker.test").build())) {
            var invalid = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build(), claims)).getTokenValue();
            assertThat(request("GET", "/api/auth/me", null, invalid).statusCode()).isEqualTo(401);
        }
        var parsed = SignedJWT.parse(initial.accessToken());
        var forged = new SignedJWT(parsed.getHeader(), parsed.getJWTClaimsSet());
        var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        forged.sign(new com.nimbusds.jose.crypto.RSASSASigner(generator.generateKeyPair().getPrivate()));
        assertThat(request("GET", "/api/auth/me", null, forged.serialize()).statusCode()).isEqualTo(401);
    }

    @Test void token_endpoints_reject_browser_simple_form_content_types_and_do_not_set_session_cookies() throws Exception {
        var form = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("password=secret")).build();
        assertThat(client.send(form, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        var response = request("POST", "/api/auth/register", registration("tenant-one"), null);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    private int demote(Actor actor) {
        try { service.changeRole(actor, actor.userId(), Role.VIEWER); return 200; } catch (AuthFailure failure) { return failure.status().value(); }
    }
    private Tokens register(String slug) { return service.register(slug, "Tenant", "admin@example.com", "Admin", PASSWORD); }
    private Actor actor(Tokens tokens) {
        var jwt = decoder.decode(tokens.accessToken()); return new Actor(UUID.fromString(jwt.getClaimAsString("tenant_id")), UUID.fromString(jwt.getSubject()));
    }
    private Map<String, String> registration(String slug) {
        return Map.of("tenantSlug", slug, "tenantName", "Tenant", "email", "admin@example.com", "displayName", "Admin", "password", PASSWORD);
    }
    private HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Tenant-Id", UUID.randomUUID().toString()).header("X-User-Id", UUID.randomUUID().toString());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private tools.jackson.databind.JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private static String keyPath() {
        try { return Files.createTempDirectory("notifyhub-auth-it").resolve("private.pem").toString(); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @AfterAll static void removeKey() throws Exception { Files.deleteIfExists(java.nio.file.Path.of(KEY_PATH)); Files.deleteIfExists(java.nio.file.Path.of(KEY_PATH).getParent()); }
}

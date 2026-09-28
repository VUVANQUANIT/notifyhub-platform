package com.vuvanquan.notifyhub.campaign;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("prod")
@Import(CampaignSecurityIT.Keys.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"campaign.scheduling.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test"})
class CampaignSecurityIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
    static final KeyPair KEY = keyPair();
    @LocalServerPort int port;

    @Test void anonymous_and_forged_tenant_headers_cannot_access_production_api() throws Exception {
        assertThat(get(null, "/api/campaigns").statusCode()).isEqualTo(401);
        assertThat(get(null, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test void valid_signed_token_with_read_scope_can_read_but_wrong_scope_cannot() throws Exception {
        assertThat(get(token(KEY, "campaigns:read", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)),
                "/api/campaigns").statusCode()).isEqualTo(200);
        assertThat(get(token(KEY, "campaigns:write", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)),
                "/api/campaigns").statusCode()).isEqualTo(403);
    }

    @Test void rejects_invalid_signature_expired_token_and_missing_tenant_claim() throws Exception {
        assertThat(get(token(keyPair(), "campaigns:read", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)),
                "/api/campaigns").statusCode()).isEqualTo(401);
        assertThat(get(token(KEY, "campaigns:read", UUID.randomUUID().toString(), Instant.now().minusSeconds(300)),
                "/api/campaigns").statusCode()).isEqualTo(401);
        assertThat(get(token(KEY, "campaigns:read", null, Instant.now().plusSeconds(60)),
                "/api/campaigns").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header("X-User-Id", UUID.randomUUID().toString());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(KeyPair key, String scope, String tenant, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://issuer.test")
                .subject(UUID.randomUUID().toString()).claim("scope", scope)
                .expirationTime(Date.from(expiry)).issueTime(Date.from(expiry.minusSeconds(600)));
        if (tenant != null) claims.claim("tenant_id", tenant);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keyPair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Keys {
        @Bean JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.test"));
            return decoder;
        }
    }
}

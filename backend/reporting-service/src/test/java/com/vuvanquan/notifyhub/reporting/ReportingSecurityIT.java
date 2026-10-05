package com.vuvanquan.notifyhub.reporting;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import com.vuvanquan.notifyhub.reporting.projection.ReportProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@Import(ReportingSecurityIT.Keys.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test")
class ReportingSecurityIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
    static final KeyPair KEY = keyPair();
    @LocalServerPort int port;
    @Autowired ReportProjection projection;

    @Test void anonymous_headers_and_wrong_scope_cannot_access_reports() throws Exception {
        assertThat(get(null, "/api/reports/summary").statusCode()).isEqualTo(401);
        assertThat(get(token(KEY, "campaigns:read", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)), "/api/reports/summary").statusCode()).isEqualTo(403);
        assertThat(get(null, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test void token_tenant_controls_report_ownership_and_forged_headers_are_ignored() throws Exception {
        UUID tenant = UUID.randomUUID(), campaign = UUID.randomUUID();
        projection.delivery(new DeliveryResultEvent(UUID.randomUUID(), "NotificationSent", 1, tenant, campaign,
                Instant.now(), UUID.randomUUID(), new DeliveryResultEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), "SMS", "SENT", 1, "sms-id", null)));
        assertThat(get(token(KEY, "reports:read", tenant.toString(), Instant.now().plusSeconds(60)), "/api/reports/campaigns/" + campaign).statusCode()).isEqualTo(200);
        assertThat(get(token(KEY, "reports:read", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)), "/api/reports/campaigns/" + campaign).statusCode()).isEqualTo(404);
    }

    @Test void rejects_invalid_signature_expired_token_and_missing_tenant() throws Exception {
        assertThat(get(token(keyPair(), "reports:read", UUID.randomUUID().toString(), Instant.now().plusSeconds(60)), "/api/reports/summary").statusCode()).isEqualTo(401);
        assertThat(get(token(KEY, "reports:read", UUID.randomUUID().toString(), Instant.now().minusSeconds(300)), "/api/reports/summary").statusCode()).isEqualTo(401);
        assertThat(get(token(KEY, "reports:read", null, Instant.now().plusSeconds(60)), "/api/reports/summary").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", UUID.randomUUID().toString()).header("X-User-Id", UUID.randomUUID().toString());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(KeyPair key, String scope, String tenant, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://issuer.test").subject(UUID.randomUUID().toString())
                .claim("scope", scope).expirationTime(Date.from(expiry)).issueTime(Date.from(expiry.minusSeconds(600)));
        if (tenant != null) claims.claim("tenant_id", tenant);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }
    private static KeyPair keyPair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean JwtDecoder decoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.test"));
            return decoder;
        }
    }
}

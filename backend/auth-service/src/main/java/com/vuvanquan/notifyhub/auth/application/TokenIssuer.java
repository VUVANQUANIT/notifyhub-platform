package com.vuvanquan.notifyhub.auth.application;

import com.vuvanquan.notifyhub.auth.configuration.AuthProperties;
import com.vuvanquan.notifyhub.auth.configuration.SigningKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final SigningKeys keys;
    private final SecureRandom random = new SecureRandom();

    public TokenIssuer(JwtEncoder encoder, AuthProperties properties, SigningKeys keys) {
        this.encoder = encoder; this.properties = properties; this.keys = keys;
    }

    String accessToken(AuthModels.User user, Instant now) {
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).subject(user.id().toString())
                .audience(List.of(properties.audience())).issuedAt(now).expiresAt(now.plus(properties.accessTtl()))
                .id(UUID.randomUUID().toString()).claim("tenant_id", user.tenantId().toString())
                .claim("scope", String.join(" ", user.role().scopes())).claim("roles", List.of(user.role().name())).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keys.signingKey().getKeyID()).build(), claims)).getTokenValue();
    }

    String refreshToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw AuthFailure.unauthorized();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

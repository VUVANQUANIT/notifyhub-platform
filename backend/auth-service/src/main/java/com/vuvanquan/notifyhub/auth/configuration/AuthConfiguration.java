package com.vuvanquan.notifyhub.auth.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean SigningKeys signingKeys(AuthProperties properties, Environment environment) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        if (local && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("local and prod profiles cannot be combined");
        }
        var issuer = URI.create(properties.issuer());
        if (issuer.getHost() == null || issuer.getQuery() != null || issuer.getFragment() != null
                || issuer.getUserInfo() != null || !("https".equals(issuer.getScheme())
                || (local && "http".equals(issuer.getScheme()) && ("localhost".equals(issuer.getHost())
                || "127.0.0.1".equals(issuer.getHost()))))) {
            throw new IllegalArgumentException("Issuer requires HTTPS; local HTTP requires a loopback host");
        }
        if (properties.audience() == null || properties.audience().isBlank()
                || properties.accessTtl().isNegative() || properties.accessTtl().isZero()
                || properties.accessTtl().compareTo(java.time.Duration.ofMinutes(15)) > 0
                || properties.refreshTtl().isNegative() || properties.refreshTtl().isZero()) {
            throw new IllegalArgumentException("Invalid token lifetime or audience");
        }
        return new SigningKeys(Path.of(properties.signingKeyPath()), local);
    }

    @Bean PasswordEncoder passwordEncoder() {
        var pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder("pbkdf2-sha256", Map.of("pbkdf2-sha256", pbkdf2));
    }

    @Bean JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean JwtDecoder jwtDecoder(SigningKeys keys, AuthProperties properties) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuer()),
                jwt -> jwt.getAudience().contains(properties.audience()) ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null))));
        return decoder;
    }
}

package com.vuvanquan.notifyhub.auth.configuration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SigningKeysTest {
    @TempDir Path directory;
    @Test void local_key_survives_restart_and_jwks_contains_no_private_material() throws Exception {
        var path = directory.resolve("private.pem");
        var first = new SigningKeys(path, true);
        var second = new SigningKeys(path, true);
        assertThat(second.publicJwks()).isEqualTo(first.publicJwks());
        assertThat(first.publicJwks().toString()).doesNotContain(" d=", " p=", " q=");
    }
    @Test void production_never_generates_missing_key() {
        var path = directory.resolve("missing.pem");
        assertThatThrownBy(() -> new SigningKeys(path, false)).isInstanceOf(NoSuchFileException.class);
        assertThat(path).doesNotExist();
    }
    @Test void weak_rsa_key_is_rejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(1024);
        var path = directory.resolve("weak.pem");
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        assertThatThrownBy(() -> new SigningKeys(path, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2048");
    }
    @Test void local_and_prod_cannot_be_combined() {
        var env = new MockEnvironment(); env.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new AuthConfiguration().signingKeys(config("https://auth.example.com"), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be combined");
    }
    @Test void production_http_issuer_and_local_non_loopback_issuer_are_rejected() {
        assertThatThrownBy(() -> new AuthConfiguration().signingKeys(config("http://auth.example.com"), new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        var env = new MockEnvironment(); env.setActiveProfiles("local");
        assertThatThrownBy(() -> new AuthConfiguration().signingKeys(config("http://auth.example.com"), env))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
    }
    private AuthProperties config(String issuer) {
        return new AuthProperties(issuer, "notifyhub", directory.resolve("private.pem").toString(), false, Duration.ofMinutes(5), Duration.ofDays(7));
    }
}

package com.vuvanquan.notifyhub.auth.control;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlCryptoTest {
    @TempDir Path directory;
    @Test void keyed_digests_bind_code_to_challenge_and_do_not_reveal_identity() throws Exception {
        var crypto = new ControlCrypto(directory.resolve("secret"), true);
        assertThat(crypto.digest("otp", "challenge-one:123456")).isNotEqualTo(crypto.digest("otp", "challenge-two:123456"));
        assertThat(crypto.digest("rate", "admin@example.com")).hasSize(64).doesNotContain("admin");
        assertThat(crypto.digest("rate", "same")).isNotEqualTo(crypto.digest("otp", "same"));
    }
    @Test void encrypted_outbox_payload_survives_restart_and_rejects_tampering() throws Exception {
        var path = directory.resolve("secret"); var crypto = new ControlCrypto(path, true);
        String encrypted = crypto.encrypt("123456");
        assertThat(encrypted).doesNotContain("123456");
        assertThat(new ControlCrypto(path, true).decrypt(encrypted)).isEqualTo("123456");
        byte[] bytes = java.util.Base64.getDecoder().decode(encrypted); bytes[bytes.length-1] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(java.util.Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
    }
    @Test void production_requires_a_provisioned_secret() {
        assertThatThrownBy(() -> new ControlCrypto(directory.resolve("missing"), false)).isInstanceOf(java.nio.file.NoSuchFileException.class);
    }
}

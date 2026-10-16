package com.vuvanquan.notifyhub.auth.control;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TotpTest {
    // RFC 6238 Appendix B SHA-1 seed, eight-digit outputs reduced to six digits.
    private final String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @Test void matches_rfc_vectors_and_rejects_replay(){
        assertThat(Totp.code(secret,59/30)).isEqualTo("287082");
        assertThat(Totp.code(secret,1111111109L/30)).isEqualTo("081804");
        assertThat(Totp.code(secret,20000000000L/30)).isEqualTo("353130");
        assertThat(Totp.verify(secret,"287082",1,-1)).isEqualTo(1);
        assertThat(Totp.verify(secret,"287082",1,1)).isEqualTo(-1);
        assertThat(Totp.verify(secret,"287082",5,-1)).isEqualTo(-1);
        assertThat(Totp.secret()).matches("[A-Z2-7]{32}");
    }
}

package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationTest {

    @Test
    void email_is_trimmed_and_normalized_to_lowercase() {
        assertThat(Destination.email("  Alice.Example@EXAMPLE.COM ").value())
                .isEqualTo("alice.example@example.com");
    }

    @Test
    void formatted_e164_phone_is_normalized() {
        assertThat(Destination.sms(" +84 (912) 345-678 ").value())
                .isEqualTo("+84912345678");
    }

    @Test
    void malformed_email_is_rejected() {
        assertThatThrownBy(() -> Destination.email("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid email destination");
    }

    @Test
    void local_phone_without_country_prefix_is_rejected() {
        assertThatThrownBy(() -> Destination.sms("0912345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SMS destination; expected E.164 format");
    }
}

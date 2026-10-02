package com.vuvanquan.notifyhub.notification.worker;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SendNotificationTaskTest {
    @Test void accepts_email_and_sms_contracts_from_campaign() {
        assertThatCode(() -> task(1, "EMAIL", "a@example.com", "Hello").validate("EMAIL")).doesNotThrowAnyException();
        assertThatCode(() -> task(1, "SMS", "+84901234567", null).validate("SMS")).doesNotThrowAnyException();
    }
    @Test void rejects_unsupported_versions_and_wrong_queue_channel() {
        assertThatThrownBy(() -> task(2, "EMAIL", "a@example.com", "Hello").validate("EMAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task(1, "EMAIL", "a@example.com", "Hello").validate("SMS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejects_header_injection_and_invalid_sms_contracts() {
        assertThatThrownBy(() -> task(1, "EMAIL", "a@example.com\r\nBcc: b@example.com", "Hello").validate("EMAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task(1, "EMAIL", "a@example.com", "Hello\nBcc: b@example.com").validate("EMAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task(1, "SMS", "0901234567", null).validate("SMS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task(1, "SMS", "+84901234567", "subject").validate("SMS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private SendNotificationTask task(int version, String channel, String destination, String subject) {
        return new SendNotificationTask(UUID.randomUUID(), version, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), channel, destination, subject, "Hello Quan", Instant.now());
    }
}

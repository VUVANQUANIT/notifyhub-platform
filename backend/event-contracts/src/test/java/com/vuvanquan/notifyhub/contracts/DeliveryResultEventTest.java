package com.vuvanquan.notifyhub.contracts;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeliveryResultEventTest {
    @Test void accepts_only_consistent_terminal_results() {
        var sent = payload("SENT", "smtp-id", null);
        assertThat(event("NotificationSent", 1, sent).payload()).isEqualTo(sent);
        assertThatThrownBy(() -> event("NotificationFailed", 1, sent)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("NotificationSent", 2, sent)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payload("RETRY_PENDING", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payload("FAILED", null, "recipient@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(event("NotificationFailed", 1, payload("FAILED", null, "SmtpUnavailable")).payload().status()).isEqualTo("FAILED");
    }
    @Test void validates_campaign_progress_and_allows_legacy_start_without_expected_count() {
        UUID id = UUID.randomUUID();
        assertThat(new CampaignProgressEvent(id, "CampaignStarted", 1, id, id, Instant.now(), id, Map.of("status", "RUNNING")).expected()).isNull();
        assertThatThrownBy(() -> new CampaignProgressEvent(id, "CampaignCompleted", 1, id, id, Instant.now(), id,
                Map.of("status", "COMPLETED", "expected", -1))).isInstanceOf(IllegalArgumentException.class);
    }
    private DeliveryResultEvent.Payload payload(String status, String reference, String failure) {
        return new DeliveryResultEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), "EMAIL", status, 1, reference, failure);
    }
    private DeliveryResultEvent event(String type, int version, DeliveryResultEvent.Payload payload) {
        UUID id = UUID.randomUUID();
        return new DeliveryResultEvent(id, type, version, id, id, Instant.now(), id, payload);
    }
}

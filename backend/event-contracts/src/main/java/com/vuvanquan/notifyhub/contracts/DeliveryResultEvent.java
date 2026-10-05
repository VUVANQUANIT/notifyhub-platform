package com.vuvanquan.notifyhub.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned wire contract; contains no destination, message content or exception text. */
public record DeliveryResultEvent(UUID eventId, String eventType, int eventVersion, UUID tenantId,
        UUID campaignId, Instant occurredAt, UUID correlationId, Payload payload) {
    public DeliveryResultEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion != 1) throw new IllegalArgumentException("Unsupported result version");
        String expected = payload.status().equals("SENT") ? "NotificationSent" : "NotificationFailed";
        if (!expected.equals(eventType)) throw new IllegalArgumentException("Result type does not match status");
    }

    public record Payload(UUID notificationId, UUID recipientId, String channel, String status,
                          int attempts, String providerReference, String failureCode) {
        public Payload {
            Objects.requireNonNull(notificationId, "notificationId");
            Objects.requireNonNull(recipientId, "recipientId");
            if (!"EMAIL".equals(channel) && !"SMS".equals(channel)) throw new IllegalArgumentException("Invalid channel");
            if (!"SENT".equals(status) && !"FAILED".equals(status)) throw new IllegalArgumentException("Result must be terminal");
            if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("Invalid attempt count");
            if ("SENT".equals(status)) {
                if (providerReference == null || providerReference.isBlank() || providerReference.length() > 1024
                        || failureCode != null) throw new IllegalArgumentException("Invalid sent result");
            } else if (providerReference != null || failureCode == null
                    || !failureCode.matches("[A-Za-z][A-Za-z0-9]{0,127}")) {
                throw new IllegalArgumentException("Invalid failed result");
            }
        }
    }
}

package com.vuvanquan.notifyhub.campaign.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(UUID eventId, String eventType, int eventVersion, UUID tenantId,
        UUID campaignId, Instant occurredAt, UUID correlationId, Map<String, Object> payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        if (eventVersion != 1) throw new IllegalArgumentException("Unsupported event version");
    }
}

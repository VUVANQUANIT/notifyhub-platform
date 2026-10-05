package com.vuvanquan.notifyhub.contracts;

import java.time.Instant;
import java.util.*;

public record CampaignProgressEvent(UUID eventId, String eventType, int eventVersion, UUID tenantId,
        UUID campaignId, Instant occurredAt, UUID correlationId, Map<String, Object> payload) {
    public CampaignProgressEvent {
        Objects.requireNonNull(eventId); Objects.requireNonNull(tenantId); Objects.requireNonNull(campaignId);
        Objects.requireNonNull(occurredAt); Objects.requireNonNull(correlationId);
        payload = Map.copyOf(Objects.requireNonNull(payload));
        String status = switch (Objects.requireNonNull(eventType)) {
            case "CampaignStarted" -> "RUNNING";
            case "CampaignCompleted" -> "COMPLETED";
            case "CampaignFailed" -> "FAILED";
            default -> throw new IllegalArgumentException("Unsupported progress event");
        };
        Object expected = payload.get("expected");
        boolean legacyStart = eventType.equals("CampaignStarted") && expected == null;
        boolean validCount = expected instanceof Number number && number.longValue() >= 1
                && number.doubleValue() == number.longValue();
        if (eventVersion != 1 || !status.equals(payload.get("status")) || (!legacyStart && !validCount)) {
            throw new IllegalArgumentException("Invalid campaign progress");
        }
    }
    public String status() { return payload.get("status").toString(); }
    public Long expected() { return payload.get("expected") == null ? null : ((Number) payload.get("expected")).longValue(); }
}

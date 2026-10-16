package com.vuvanquan.notifyhub.notification.worker;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Wire contract v1 owned by each service; no dependency on Campaign's domain classes. */
public record SendNotificationTask(UUID notificationId, int taskVersion, UUID tenantId, UUID campaignId,
        UUID recipientId, UUID correlationId, String channel, String destination,
        String subject, String body, Instant createdAt) {
    public void validate(String expectedChannel) {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (taskVersion != 1 || !expectedChannel.equals(channel)
                || destination == null || destination.isBlank() || destination.contains("\r") || destination.contains("\n")
                || body == null || body.isBlank()) {
            throw new IllegalArgumentException("Invalid notification task");
        }
        if ("EMAIL".equals(channel)) {
            if (subject == null || subject.isBlank() || subject.contains("\r") || subject.contains("\n")) {
                throw new IllegalArgumentException("Invalid email subject");
            }
        } else if (!"SMS".equals(channel) || subject != null || !destination.matches("\\+[1-9]\\d{7,14}")) {
            throw new IllegalArgumentException("Invalid SMS task");
        }
    }
}

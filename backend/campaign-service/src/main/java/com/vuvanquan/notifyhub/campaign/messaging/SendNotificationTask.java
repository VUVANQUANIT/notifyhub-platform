package com.vuvanquan.notifyhub.campaign.messaging;

import java.time.Instant;
import java.util.UUID;

public record SendNotificationTask(UUID notificationId, int taskVersion, UUID tenantId, UUID campaignId,
        UUID recipientId, UUID correlationId, String channel, String destination,
        String subject, String body, Instant createdAt) {}

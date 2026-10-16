package com.vuvanquan.notifyhub.campaign.application;

import java.util.Objects;
import java.util.UUID;

public record Actor(UUID tenantId, UUID userId) {
    public Actor {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
    }
}

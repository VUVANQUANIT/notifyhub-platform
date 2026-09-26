package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "Tenant id must not be null");
    }
}

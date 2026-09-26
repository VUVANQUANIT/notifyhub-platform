package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "User id must not be null");
    }
}

package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;

public record CampaignName(String value) {

    public CampaignName {
        Objects.requireNonNull(value, "Campaign name must not be null");
        value = value.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Campaign name must not be blank");
        }
    }
}

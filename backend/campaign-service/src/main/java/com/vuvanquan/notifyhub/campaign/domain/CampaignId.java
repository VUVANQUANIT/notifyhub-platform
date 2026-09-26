package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;
import java.util.UUID;

public record CampaignId(UUID value) {

    public CampaignId {
        Objects.requireNonNull(value, "Campaign id must not be null");
    }
}

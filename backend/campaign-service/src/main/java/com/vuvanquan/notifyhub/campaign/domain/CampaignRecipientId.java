package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;
import java.util.UUID;

public record CampaignRecipientId(UUID value) {

    public CampaignRecipientId {
        Objects.requireNonNull(value, "Campaign recipient id must not be null");
    }
}

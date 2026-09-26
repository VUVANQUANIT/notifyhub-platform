package com.vuvanquan.notifyhub.campaign.domain;

public record CampaignReadiness(long validRecipientCount, boolean importInProgress) {

    public CampaignReadiness {
        if (validRecipientCount < 0) {
            throw new IllegalArgumentException("Valid recipient count must not be negative");
        }
    }
}

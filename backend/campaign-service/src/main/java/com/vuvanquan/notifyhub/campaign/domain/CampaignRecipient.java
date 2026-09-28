package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;

public record CampaignRecipient(
        CampaignRecipientId id,
        CampaignId campaignId,
        TenantId tenantId,
        Destination destination,
        PersonalizationData personalizationData,
        RecipientImportId sourceImportId
) {

    public CampaignRecipient {
        Objects.requireNonNull(id, "Campaign recipient id must not be null");
        Objects.requireNonNull(campaignId, "Campaign id must not be null");
        Objects.requireNonNull(tenantId, "Tenant id must not be null");
        Objects.requireNonNull(destination, "Destination must not be null");
        Objects.requireNonNull(personalizationData, "Personalization data must not be null");
        Objects.requireNonNull(sourceImportId, "Source import id must not be null");
    }
}

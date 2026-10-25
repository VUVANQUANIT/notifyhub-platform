package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignRecipientTest {

    @Test
    void personalization_data_is_a_defensive_immutable_copy() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("firstName", "Quan");
        PersonalizationData data = new PersonalizationData(source);

        source.put("firstName", "Changed");

        assertThat(data.values()).containsEntry("firstName", "Quan");
        assertThatThrownBy(() -> data.values().put("lastName", "Vu"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void campaign_recipient_requires_traceability_to_an_import() {
        assertThatThrownBy(() -> new CampaignRecipient(
                new CampaignRecipientId(UUID.randomUUID()),
                new CampaignId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Destination.email("recipient@example.com"),
                PersonalizationData.empty(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Source import id must not be null");
    }
}

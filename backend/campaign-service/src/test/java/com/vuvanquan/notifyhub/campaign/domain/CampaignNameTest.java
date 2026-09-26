package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignNameTest {

    @Test
    void campaign_name_is_trimmed() {
        assertThat(new CampaignName("  Welcome campaign  ").value()).isEqualTo("Welcome campaign");
    }

    @Test
    void blank_campaign_name_is_rejected() {
        assertThatThrownBy(() -> new CampaignName(" \t "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Campaign name must not be blank");
    }

    @Test
    void null_campaign_name_is_rejected() {
        assertThatThrownBy(() -> new CampaignName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Campaign name must not be null");
    }
}

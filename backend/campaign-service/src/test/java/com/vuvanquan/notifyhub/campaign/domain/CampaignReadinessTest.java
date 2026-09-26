package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignReadinessTest {

    @Test
    void recipient_count_cannot_be_negative() {
        assertThatThrownBy(() -> new CampaignReadiness(-1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Valid recipient count must not be negative");
    }
}

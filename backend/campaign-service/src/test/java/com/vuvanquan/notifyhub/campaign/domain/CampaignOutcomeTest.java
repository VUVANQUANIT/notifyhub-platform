package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CampaignOutcomeTest {
    @Test void waits_for_complete_dispatch_and_all_terminal_deliveries() {
        assertThat(CampaignOutcome.resolve(false, 2, 2, 0)).isEmpty();
        assertThat(CampaignOutcome.resolve(true, 2, 1, 0)).isEmpty();
        assertThat(CampaignOutcome.resolve(true, 0, 0, 0)).isEmpty();
    }

    @Test void completes_when_all_sent_and_fails_after_all_finish_if_any_failed() {
        assertThat(CampaignOutcome.resolve(true, 2, 2, 0)).contains(CampaignStatus.COMPLETED);
        assertThat(CampaignOutcome.resolve(true, 2, 1, 1)).contains(CampaignStatus.FAILED);
        assertThat(CampaignOutcome.resolve(true, 2, 0, 2)).contains(CampaignStatus.FAILED);
    }

    @Test void rejects_inconsistent_counts() {
        assertThatThrownBy(() -> CampaignOutcome.resolve(true, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CampaignOutcome.resolve(true, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

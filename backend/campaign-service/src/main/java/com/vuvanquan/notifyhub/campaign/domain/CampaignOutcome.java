package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Optional;

public final class CampaignOutcome {
    private CampaignOutcome() {}

    public static Optional<CampaignStatus> resolve(boolean dispatchComplete, long total, long sent, long failed) {
        if (total < 0 || sent < 0 || failed < 0 || sent > total || failed > total - sent) {
            throw new IllegalArgumentException("Inconsistent delivery totals");
        }
        if (!dispatchComplete || total == 0 || sent + failed != total) return Optional.empty();
        return Optional.of(failed == 0 ? CampaignStatus.COMPLETED : CampaignStatus.FAILED);
    }
}

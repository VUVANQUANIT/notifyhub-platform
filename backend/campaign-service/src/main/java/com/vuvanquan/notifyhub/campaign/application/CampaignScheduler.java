package com.vuvanquan.notifyhub.campaign.application;

import com.vuvanquan.notifyhub.campaign.domain.CampaignStatus;
import com.vuvanquan.notifyhub.campaign.persistence.CampaignRepository;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import java.time.Clock;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="campaign.scheduling.enabled", havingValue="true", matchIfMissing=true)
public class CampaignScheduler {
    private static final Logger log = LoggerFactory.getLogger(CampaignScheduler.class);
    private final CampaignRepository repository;
    private final CampaignApplicationService service;
    private final Clock clock;

    public CampaignScheduler(CampaignRepository repository, CampaignApplicationService service, Clock clock) {
        this.repository = repository; this.service = service; this.clock = clock;
    }

    @Scheduled(fixedDelayString="${campaign.scheduling.delay-ms:5000}")
    public void activate() {
        for (var campaign : repository.findTop100ByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                CampaignStatus.SCHEDULED, clock.instant())) {
            try {
                service.activateDue(campaign.tenantId, campaign.id);
            } catch (RuntimeException exception) {
                log.error("Scheduled campaign activation failed: {}", campaign.id, exception);
            }
        }
    }
}

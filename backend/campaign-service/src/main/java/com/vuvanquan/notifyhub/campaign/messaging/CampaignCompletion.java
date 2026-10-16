package com.vuvanquan.notifyhub.campaign.messaging;

import com.vuvanquan.notifyhub.campaign.domain.*;
import com.vuvanquan.notifyhub.campaign.persistence.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class CampaignCompletion {
    private final CampaignRepository campaigns;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    public CampaignCompletion(CampaignRepository campaigns, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.campaigns = campaigns; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeLocked(CampaignEntity campaign) {
        if (campaign.status != CampaignStatus.RUNNING) return;
        var jobs = jdbc.queryForList("""
                SELECT correlation_id FROM campaign.dispatch_jobs
                WHERE tenant_id=? AND campaign_id=? AND completed_at IS NOT NULL
                """, UUID.class, campaign.tenantId, campaign.id);
        if (jobs.isEmpty()) return;
        long expected = jdbc.queryForObject("SELECT count(*) FROM campaign.campaign_recipients WHERE tenant_id=? AND campaign_id=?",
                Long.class, campaign.tenantId, campaign.id);
        long sent = count(campaign, "SENT");
        long failed = count(campaign, "FAILED");
        var outcome = CampaignOutcome.resolve(true, expected, sent, failed);
        if (outcome.isEmpty()) return;
        var domain = campaign.toDomain();
        if (outcome.get() == CampaignStatus.COMPLETED) domain.complete(); else domain.fail();
        campaign.apply(domain);
        campaigns.flush();
        jdbc.update("""
                INSERT INTO campaign.outbox_events(event_id,tenant_id,campaign_id,event_type,occurred_at,correlation_id,payload)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), campaign.tenantId, campaign.id,
                outcome.get() == CampaignStatus.COMPLETED ? "CampaignCompleted" : "CampaignFailed",
                Timestamp.from(clock.instant()), jobs.getFirst(), json.writeValueAsString(Map.of(
                        "status", outcome.get().name(), "expected", expected, "sent", sent, "failed", failed)));
    }

    // Results may arrive before the last dispatcher batch commits. Sweep closes that race without replay.
    @Transactional
    public boolean finalizeOne() {
        var rows = jdbc.query("""
                SELECT c.tenant_id,c.id FROM campaign.campaigns c
                JOIN campaign.dispatch_jobs j ON j.tenant_id=c.tenant_id AND j.campaign_id=c.id
                WHERE c.status='RUNNING' AND j.completed_at IS NOT NULL
                AND EXISTS(SELECT 1 FROM campaign.delivery_results r WHERE r.tenant_id=c.tenant_id AND r.campaign_id=c.id)
                AND NOT EXISTS(SELECT 1 FROM campaign.campaign_recipients p
                    WHERE p.tenant_id=c.tenant_id AND p.campaign_id=c.id
                    AND NOT EXISTS(SELECT 1 FROM campaign.delivery_results r
                        WHERE r.tenant_id=p.tenant_id AND r.campaign_id=p.campaign_id AND r.recipient_id=p.id))
                ORDER BY c.created_at,c.id LIMIT 1 FOR UPDATE OF c SKIP LOCKED
                """, (rs, row) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
        if (rows.isEmpty()) return false;
        var id = rows.getFirst();
        completeLocked(campaigns.lock(id[0], id[1]).orElseThrow());
        return true;
    }

    private long count(CampaignEntity campaign, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign.delivery_results WHERE tenant_id=? AND campaign_id=? AND status=?",
                Long.class, campaign.tenantId, campaign.id, status);
    }
}

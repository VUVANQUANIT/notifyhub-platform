package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class DispatchInbox {
    private final JdbcTemplate jdbc;
    public DispatchInbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void accept(EventEnvelope event) {
        var states = jdbc.queryForList("SELECT status FROM campaign.campaigns WHERE tenant_id=? AND id=?",
                String.class, event.tenantId(), event.campaignId());
        if (states.isEmpty() || !states.getFirst().equals("RUNNING")) {
            // A previously accepted job remains valid when an old Kafka record is replayed later.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM campaign.dispatch_jobs WHERE tenant_id=? AND campaign_id=?)
                    """, Boolean.class, event.tenantId(), event.campaignId()))) return;
            throw new IllegalArgumentException("CampaignStarted must reference a running campaign in its tenant");
        }
        jdbc.update("""
                INSERT INTO campaign.dispatch_jobs(event_id,tenant_id,campaign_id,correlation_id,occurred_at)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.tenantId(), event.campaignId(), event.correlationId(),
                Timestamp.from(event.occurredAt()));
    }
}

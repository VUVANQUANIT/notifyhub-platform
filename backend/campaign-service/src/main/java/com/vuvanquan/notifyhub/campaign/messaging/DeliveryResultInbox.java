package com.vuvanquan.notifyhub.campaign.messaging;

import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import com.vuvanquan.notifyhub.campaign.persistence.CampaignRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class DeliveryResultInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CampaignRepository campaigns;
    private final CampaignCompletion completion;
    public DeliveryResultInbox(JdbcTemplate jdbc, ObjectMapper json, CampaignRepository campaigns, CampaignCompletion completion) {
        this.jdbc = jdbc; this.json = json; this.campaigns = campaigns; this.completion = completion;
    }

    @Transactional
    public void accept(DeliveryResultEvent event) {
        var campaign = campaigns.lock(event.tenantId(), event.campaignId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown result campaign"));
        var payload = event.payload();
        var tasks = jdbc.queryForList("""
                SELECT payload::text FROM campaign.delivery_tasks
                WHERE notification_id=? AND tenant_id=? AND campaign_id=? AND recipient_id=?
                """, String.class, payload.notificationId(), event.tenantId(), event.campaignId(), payload.recipientId());
        if (tasks.isEmpty()) throw new IllegalArgumentException("Result does not match a campaign task");
        var task = json.readValue(tasks.getFirst(), SendNotificationTask.class);
        if (!task.channel().equals(payload.channel()) || !task.correlationId().equals(event.correlationId())) {
            throw new IllegalArgumentException("Result ownership mismatch");
        }
        var previous = jdbc.queryForList("SELECT envelope::text FROM campaign.delivery_results WHERE notification_id=?",
                String.class, payload.notificationId());
        if (!previous.isEmpty()) {
            var original = json.readValue(previous.getFirst(), DeliveryResultEvent.class);
            if (!original.payload().equals(payload) || !original.occurredAt().equals(event.occurredAt())) {
                throw new IllegalArgumentException("Conflicting terminal result");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO campaign.delivery_results(notification_id,event_id,tenant_id,campaign_id,recipient_id,status,envelope)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, payload.notificationId(), event.eventId(), event.tenantId(), event.campaignId(), payload.recipientId(),
                payload.status(), json.writeValueAsString(event));
        completion.completeLocked(campaign);
    }
}

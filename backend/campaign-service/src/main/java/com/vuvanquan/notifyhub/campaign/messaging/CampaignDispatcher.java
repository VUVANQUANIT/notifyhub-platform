package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class CampaignDispatcher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int batchSize;

    public CampaignDispatcher(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${campaign.messaging.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        this.jdbc = jdbc; this.json = json; this.batchSize = batchSize;
    }

    @Transactional
    public boolean dispatchOneBatch() {
        var jobs = jdbc.query("""
                SELECT * FROM campaign.dispatch_jobs WHERE completed_at IS NULL
                ORDER BY occurred_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Job(rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getObject("last_recipient_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant()));
        if (jobs.isEmpty()) return false;
        Job job = jobs.getFirst();
        var tasks = jdbc.query("""
                SELECT r.id, r.channel, r.destination, r.personalization, c.subject, c.body
                FROM campaign.campaign_recipients r
                JOIN campaign.campaigns c ON c.tenant_id=r.tenant_id AND c.id=r.campaign_id
                WHERE r.tenant_id=? AND r.campaign_id=? AND (?::uuid IS NULL OR r.id > ?::uuid)
                ORDER BY r.id LIMIT ?
                """, (rs, row) -> {
            UUID recipientId = rs.getObject("id", UUID.class);
            UUID notificationId = UUID.nameUUIDFromBytes((job.tenantId() + ":" + job.campaignId() + ":" + recipientId)
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, String> variables = json.readValue(rs.getString("personalization"), new TypeReference<>() {});
            return new SendNotificationTask(notificationId, 1, job.tenantId(), job.campaignId(), recipientId,
                    job.correlationId(), rs.getString("channel"), rs.getString("destination"),
                    MessageRenderer.render(rs.getString("subject"), variables),
                    MessageRenderer.render(rs.getString("body"), variables), job.occurredAt());
        }, job.tenantId(), job.campaignId(), job.lastRecipientId(), job.lastRecipientId(), batchSize);
        for (var task : tasks) {
            String routingKey = switch (task.channel()) {
                case "EMAIL" -> MessagingConfiguration.EMAIL_KEY;
                case "SMS" -> MessagingConfiguration.SMS_KEY;
                default -> throw new IllegalStateException("Unknown task channel");
            };
            jdbc.update("""
                    INSERT INTO campaign.delivery_tasks(notification_id,tenant_id,campaign_id,recipient_id,routing_key,payload)
                    VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT (tenant_id,campaign_id,recipient_id) DO NOTHING
                    """, task.notificationId(), task.tenantId(), task.campaignId(), task.recipientId(), routingKey,
                    json.writeValueAsString(task));
        }
        if (!tasks.isEmpty()) {
            jdbc.update("UPDATE campaign.dispatch_jobs SET last_recipient_id=? WHERE event_id=?",
                    tasks.getLast().recipientId(), job.eventId());
        }
        if (tasks.size() < batchSize) {
            jdbc.update("UPDATE campaign.dispatch_jobs SET completed_at=now() WHERE event_id=?", job.eventId());
        }
        return true;
    }

    private record Job(UUID eventId, UUID tenantId, UUID campaignId, UUID correlationId,
                       UUID lastRecipientId, Instant occurredAt) {}
}

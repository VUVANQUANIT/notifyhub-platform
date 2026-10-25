package com.vuvanquan.notifyhub.notification.events;

import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import com.vuvanquan.notifyhub.notification.worker.SendNotificationTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
public class ResultOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ResultOutbox(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(SendNotificationTask task, String status, int attempts, String reference, String failure, Instant at) {
        var event = new DeliveryResultEvent(UUID.randomUUID(), status.equals("SENT") ? "NotificationSent" : "NotificationFailed",
                1, task.tenantId(), task.campaignId(), at, task.correlationId(),
                new DeliveryResultEvent.Payload(task.notificationId(), task.recipientId(), task.channel(), status,
                        attempts, reference, failure));
        jdbc.update("""
                INSERT INTO notification.result_outbox(event_id,notification_id,campaign_id,envelope,occurred_at)
                VALUES (?,?,?,?::jsonb,?) ON CONFLICT (notification_id) DO NOTHING
                """, event.eventId(), task.notificationId(), task.campaignId(), json.writeValueAsString(event), Timestamp.from(at));
    }
}

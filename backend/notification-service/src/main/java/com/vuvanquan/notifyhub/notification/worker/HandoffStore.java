package com.vuvanquan.notifyhub.notification.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import static com.vuvanquan.notifyhub.notification.worker.WorkerConfiguration.*;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
public class HandoffStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public HandoffStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void retry(SendNotificationTask task, byte[] payload, int attempt, Instant dueAt, String error) {
        insert("retry:" + task.notificationId() + ":" + attempt, task.notificationId(), EXCHANGE,
                task.channel(), payload, attempt, dueAt, error, true);
    }

    public void failed(SendNotificationTask task, byte[] payload, int attempt, String error) {
        insert("failed:" + task.notificationId(), task.notificationId(), DEAD_EXCHANGE,
                task.channel(), payload, attempt, DeliveryTime.now(clock), error, false);
    }

    public void rejected(byte[] payload, String channel, String error) {
        insert("rejected:" + channel + ":" + PayloadIdentity.hash(payload), null, DEAD_EXCHANGE,
                channel, payload, 0, DeliveryTime.now(clock), error, false);
    }

    private void insert(String key, UUID notificationId, String exchange, String channel, byte[] payload,
            int attempt, Instant availableAt, String error, boolean reschedule) {
        jdbc.update("""
                INSERT INTO notification.delivery_handoffs
                (id,dedupe_key,notification_id,exchange_name,routing_key,payload,attempt_number,failure_type,available_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key)
                """ + (reschedule ? "DO UPDATE SET available_at=EXCLUDED.available_at,published_at=NULL" : "DO NOTHING"),
                UUID.randomUUID(), key, notificationId, exchange,
                "EMAIL".equals(channel) ? EMAIL_KEY : SMS_KEY, payload, attempt, error, Timestamp.from(availableAt));
    }
}

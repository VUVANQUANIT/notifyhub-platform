package com.vuvanquan.notifyhub.notification.worker;

import com.vuvanquan.notifyhub.notification.events.ResultOutbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class DeliveryWorker {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EmailDeliveryProvider email;
    private final SimulatedSmsProvider sms;
    private final DeliveryPolicy policy;
    private final HandoffStore handoffs;
    private final Clock clock;
    private final ResultOutbox results;

    public DeliveryWorker(JdbcTemplate jdbc, ObjectMapper json, EmailDeliveryProvider email,
            SimulatedSmsProvider sms, DeliveryPolicy policy, HandoffStore handoffs, Clock clock, ResultOutbox results) {
        this.jdbc = jdbc;
        this.json = json;
        this.email = email;
        this.sms = sms;
        this.policy = policy;
        this.handoffs = handoffs;
        this.clock = clock;
        this.results = results;
    }

    @Transactional
    public void deliver(byte[] body, String expectedChannel) {
        SendNotificationTask task;
        try {
            task = json.readValue(body, SendNotificationTask.class);
            task.validate(expectedChannel);
        } catch (RuntimeException exception) {
            handoffs.rejected(body, expectedChannel, "InvalidTask");
            return;
        }
        // Compare the logical contract, not JSON whitespace or field ordering.
        byte[] payload = json.writeValueAsBytes(task);
        String hash = PayloadIdentity.hash(payload);
        Instant now = DeliveryTime.now(clock);
        jdbc.update("""
                INSERT INTO notification.deliveries
                (notification_id,tenant_id,campaign_id,recipient_id,correlation_id,channel,payload_hash,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PENDING',?,?) ON CONFLICT (notification_id) DO NOTHING
                """, task.notificationId(), task.tenantId(), task.campaignId(), task.recipientId(),
                task.correlationId(), task.channel(), hash, Timestamp.from(now), Timestamp.from(now));
        // The row lock also serializes duplicate messages across worker instances while calling the provider.
        var state = jdbc.queryForObject("""
                SELECT payload_hash,status,attempts,next_attempt_at FROM notification.deliveries
                WHERE notification_id=? FOR UPDATE
                """, (rs, row) -> new State(rs.getString("payload_hash"), rs.getString("status"),
                rs.getInt("attempts"), rs.getTimestamp("next_attempt_at") == null ? null
                        : rs.getTimestamp("next_attempt_at").toInstant()), task.notificationId());
        Objects.requireNonNull(state);
        if (!state.hash().equals(hash)) {
            handoffs.rejected(body, expectedChannel, "NotificationIdentityConflict");
            return;
        }
        if (state.status().equals("SENT") || state.status().equals("FAILED")) return;
        if (state.nextAttemptAt() != null && now.isBefore(state.nextAttemptAt())) {
            // Keep a durable retry even if another publisher's clock was ahead of this consumer.
            handoffs.retry(task, payload, state.attempts(), state.nextAttemptAt(), "RetryNotDue");
            return;
        }
        if (state.attempts() >= policy.maxAttempts()) {
            fail(task, payload, state.attempts(), "AttemptLimit", now);
            return;
        }
        int attempt = state.attempts() + 1;
        String providerReference;
        try {
            providerReference = task.channel().equals("EMAIL") ? email.send(task) : sms.send(task);
        } catch (DeliveryFailure failure) {
            if (policy.shouldRetry(attempt, failure.retryable())) {
                Instant due = DeliveryTime.now(clock).plus(policy.retryDelay(attempt));
                jdbc.update("""
                        UPDATE notification.deliveries SET status='RETRY_PENDING',attempts=?,next_attempt_at=?,
                        last_error=?,updated_at=? WHERE notification_id=?
                        """, attempt, Timestamp.from(due), failure.getMessage(), Timestamp.from(DeliveryTime.now(clock)), task.notificationId());
                handoffs.retry(task, payload, attempt, due, failure.getMessage());
            } else {
                fail(task, payload, attempt, failure.getMessage(), DeliveryTime.now(clock));
            }
            return;
        }
        Instant completedAt = DeliveryTime.now(clock);
        jdbc.update("""
                UPDATE notification.deliveries SET status='SENT',attempts=?,next_attempt_at=NULL,
                provider_reference=?,last_error=NULL,updated_at=?,completed_at=? WHERE notification_id=?
                """, attempt, providerReference, Timestamp.from(completedAt), Timestamp.from(completedAt), task.notificationId());
        results.record(task, "SENT", attempt, providerReference, null, completedAt);
    }

    private void fail(SendNotificationTask task, byte[] payload, int attempt, String error, Instant at) {
        jdbc.update("""
                UPDATE notification.deliveries SET status='FAILED',attempts=?,next_attempt_at=NULL,
                last_error=?,updated_at=?,completed_at=? WHERE notification_id=?
                """, attempt, error, Timestamp.from(at), Timestamp.from(at), task.notificationId());
        handoffs.failed(task, payload, attempt, error);
        results.record(task, "FAILED", attempt, null, error, at);
    }

    private record State(String hash, String status, int attempts, Instant nextAttemptAt) {}
}

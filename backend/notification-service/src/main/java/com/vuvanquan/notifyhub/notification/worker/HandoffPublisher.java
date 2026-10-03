package com.vuvanquan.notifyhub.notification.worker;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class HandoffPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    public HandoffPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, Clock clock) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.clock = clock;
    }

    @Transactional
    public boolean publishOne() {
        var rows = jdbc.query("""
                SELECT * FROM notification.delivery_handoffs
                WHERE published_at IS NULL AND available_at<=?
                ORDER BY available_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("notification_id", UUID.class),
                rs.getString("exchange_name"), rs.getString("routing_key"), rs.getBytes("payload"),
                rs.getInt("attempt_number"), rs.getString("failure_type"), rs.getInt("publish_attempts")),
                Timestamp.from(DeliveryTime.now(clock)));
        if (rows.isEmpty()) return false;
        Pending handoff = rows.getFirst();
        var message = MessageBuilder.withBody(handoff.payload()).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(handoff.notificationId() == null ? handoff.id().toString() : handoff.notificationId().toString())
                .setHeader("x-notifyhub-attempt", handoff.attempt()).setHeader("x-notifyhub-failure", handoff.error()).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(handoff.exchange(), handoff.key(), message, correlation);
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not confirm routing to a queue");
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            long delayMillis = Math.min(60000, 1000L << Math.min(handoff.publishAttempts(), 6));
            jdbc.update("""
                    UPDATE notification.delivery_handoffs SET publish_attempts=publish_attempts+1,
                    available_at=?,last_error=? WHERE id=?
                    """, Timestamp.from(DeliveryTime.now(clock).plusMillis(delayMillis)), exception.getClass().getSimpleName(), handoff.id());
            return true;
        }
        jdbc.update("""
                UPDATE notification.delivery_handoffs SET published_at=?,publish_attempts=publish_attempts+1,
                last_error=NULL WHERE id=?
                """, Timestamp.from(DeliveryTime.now(clock)), handoff.id());
        return true;
    }

    private record Pending(UUID id, UUID notificationId, String exchange, String key, byte[] payload,
                           int attempt, String error, int publishAttempts) {}
}

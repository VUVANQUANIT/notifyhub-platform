package com.vuvanquan.notifyhub.notification.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "notification.events.enabled", havingValue = "true")
public class ResultPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;
    public ResultPublisher(JdbcTemplate jdbc, KafkaTemplate<Object, Object> kafka,
            @Value("${notification.events.topic:notifyhub.notification.events.v1}") String topic) {
        this.jdbc = jdbc; this.kafka = kafka; this.topic = topic;
    }

    @Transactional
    public boolean publishOne() {
        var rows = jdbc.query("""
                SELECT * FROM notification.result_outbox WHERE published_at IS NULL AND next_attempt_at<=now()
                ORDER BY occurred_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Pending(rs.getObject("event_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getString("envelope"), rs.getInt("publish_attempts")));
        if (rows.isEmpty()) return false;
        var event = rows.getFirst();
        try {
            kafka.send(topic, event.campaignId().toString(), event.envelope()).get(12, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            int delay = 1 << Math.min(event.attempts(), 6);
            jdbc.update("""
                    UPDATE notification.result_outbox SET publish_attempts=publish_attempts+1,
                    next_attempt_at=now()+(? * interval '1 second'),last_error=? WHERE event_id=?
                    """, Math.min(delay, 60), error.getClass().getSimpleName(), event.id());
            return true;
        }
        jdbc.update("""
                UPDATE notification.result_outbox SET published_at=now(),publish_attempts=publish_attempts+1,
                last_error=NULL WHERE event_id=?
                """, event.id());
        return true;
    }

    private record Pending(UUID id, UUID campaignId, String envelope, int attempts) {}
}

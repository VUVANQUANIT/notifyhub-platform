package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<Object, Object> kafka;
    private final ObjectMapper json;
    private final String topic;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<Object, Object> kafka, ObjectMapper json,
            @Value("${campaign.messaging.topic}") String topic) {
        this.jdbc = jdbc; this.kafka = kafka; this.json = json; this.topic = topic;
    }

    @Transactional
    public boolean publishOne() {
        var rows = jdbc.query("""
                SELECT * FROM campaign.outbox_events
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY occurred_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Pending(new EventEnvelope(rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getInt("event_version"), rs.getObject("tenant_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("correlation_id", UUID.class), json.readValue(rs.getString("payload"),
                        new TypeReference<Map<String, Object>>() {})), rs.getInt("publish_attempts")));
        if (rows.isEmpty()) return false;
        var pending = rows.getFirst();
        var event = pending.event();
        try {
            kafka.send(topic, event.campaignId().toString(), json.writeValueAsString(event))
                    .get(12, TimeUnit.SECONDS);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            jdbc.update("""
                    UPDATE campaign.outbox_events SET publish_attempts=publish_attempts+1,
                    next_attempt_at=?, last_error=? WHERE event_id=?
                    """, Timestamp.from(PublishRetry.nextAttempt(pending.attempts())),
                    PublishRetry.error(exception), event.eventId());
            return true;
        }
        jdbc.update("""
                UPDATE campaign.outbox_events SET published_at=now(), publish_attempts=publish_attempts+1,
                last_error=NULL WHERE event_id=?
                """, event.eventId());
        return true;
    }

    private record Pending(EventEnvelope event, int attempts) {}
}

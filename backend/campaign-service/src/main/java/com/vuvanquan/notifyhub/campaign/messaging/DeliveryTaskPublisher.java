package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class DeliveryTaskPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    public DeliveryTaskPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc; this.rabbit = rabbit;
    }

    @Transactional
    public boolean publishOne() {
        var tasks = jdbc.query("""
                SELECT notification_id,routing_key,payload,publish_attempts FROM campaign.delivery_tasks
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY created_at,notification_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Pending(rs.getObject("notification_id", UUID.class),
                rs.getString("routing_key"), rs.getString("payload"), rs.getInt("publish_attempts")));
        if (tasks.isEmpty()) return false;
        var task = tasks.getFirst();
        var message = MessageBuilder.withBody(task.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                .setMessageId(task.id().toString()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(MessagingConfiguration.EXCHANGE, task.routingKey(), message, correlation);
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not confirm routing to a queue");
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            jdbc.update("""
                    UPDATE campaign.delivery_tasks SET publish_attempts=publish_attempts+1,
                    next_attempt_at=?, last_error=? WHERE notification_id=?
                    """, Timestamp.from(PublishRetry.nextAttempt(task.attempts())),
                    PublishRetry.error(exception), task.id());
            return true;
        }
        jdbc.update("""
                UPDATE campaign.delivery_tasks SET published_at=now(),publish_attempts=publish_attempts+1,
                last_error=NULL WHERE notification_id=?
                """, task.id());
        return true;
    }

    private record Pending(UUID id, String routingKey, String payload, int attempts) {}
}

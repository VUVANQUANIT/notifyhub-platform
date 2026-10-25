package com.vuvanquan.notifyhub.notification.worker;

import com.rabbitmq.client.Channel;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import static com.vuvanquan.notifyhub.notification.worker.WorkerConfiguration.*;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class NotificationTaskListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationTaskListener.class);
    private final DeliveryWorker worker;
    public NotificationTaskListener(DeliveryWorker worker) { this.worker = worker; }

    @RabbitListener(queues = EMAIL_QUEUE, ackMode = "MANUAL")
    public void email(Message message, Channel channel) throws IOException { receive(message, channel, "EMAIL"); }

    @RabbitListener(queues = SMS_QUEUE, ackMode = "MANUAL")
    public void sms(Message message, Channel channel) throws IOException { receive(message, channel, "SMS"); }

    private void receive(Message message, Channel channel, String expectedChannel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            // Return only after provider result or retry/DLQ handoff has committed to PostgreSQL.
            worker.deliver(message.getBody(), expectedChannel);
        } catch (RuntimeException exception) {
            log.warn("Delivery transaction failed; requeueing message ({})", exception.getClass().getSimpleName());
            channel.basicNack(tag, false, true);
            return;
        }
        channel.basicAck(tag, false);
    }
}

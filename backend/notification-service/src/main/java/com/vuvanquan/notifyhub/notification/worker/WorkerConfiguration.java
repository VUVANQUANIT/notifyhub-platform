package com.vuvanquan.notifyhub.notification.worker;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class WorkerConfiguration {
    public static final String EXCHANGE = "notifyhub.notification.tasks";
    public static final String EMAIL_QUEUE = "notifyhub.notification.email";
    public static final String SMS_QUEUE = "notifyhub.notification.sms";
    public static final String EMAIL_KEY = "notification.send.email";
    public static final String SMS_KEY = "notification.send.sms";
    public static final String DEAD_EXCHANGE = "notifyhub.notification.dead-letter";
    public static final String EMAIL_DLQ = EMAIL_QUEUE + ".dlq";
    public static final String SMS_DLQ = SMS_QUEUE + ".dlq";

    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock deliveryClock() { return Clock.systemUTC(); }

    @Bean
    DeliveryPolicy deliveryPolicy(@Value("${notification.worker.max-attempts:4}") int attempts,
            @Value("${notification.worker.initial-retry-delay:1s}") Duration initial,
            @Value("${notification.worker.max-retry-delay:60s}") Duration maximum) {
        return new DeliveryPolicy(attempts, initial, maximum);
    }

    @Bean
    Declarables workerTopology() {
        // Primary topology must match Campaign's declarations exactly (no extra queue arguments).
        var tasks = new DirectExchange(EXCHANGE, true, false);
        var dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        var email = QueueBuilder.durable(EMAIL_QUEUE).build();
        var sms = QueueBuilder.durable(SMS_QUEUE).build();
        var emailDead = QueueBuilder.durable(EMAIL_DLQ).build();
        var smsDead = QueueBuilder.durable(SMS_DLQ).build();
        return new Declarables(tasks, dead, email, sms, emailDead, smsDead,
                BindingBuilder.bind(email).to(tasks).with(EMAIL_KEY),
                BindingBuilder.bind(sms).to(tasks).with(SMS_KEY),
                BindingBuilder.bind(emailDead).to(dead).with(EMAIL_KEY),
                BindingBuilder.bind(smsDead).to(dead).with(SMS_KEY));
    }
}

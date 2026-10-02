package com.vuvanquan.notifyhub.campaign.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class MessagingConfiguration {
    public static final String EXCHANGE = "notifyhub.notification.tasks";
    public static final String EMAIL_QUEUE = "notifyhub.notification.email";
    public static final String SMS_QUEUE = "notifyhub.notification.sms";
    public static final String EMAIL_KEY = "notification.send.email";
    public static final String SMS_KEY = "notification.send.sms";

    @Bean
    KafkaAdmin.NewTopics campaignTopics(
            @Value("${campaign.messaging.topic}") String topic,
            @Value("${campaign.messaging.dead-letter-topic}") String dlt,
            @Value("${campaign.messaging.partitions:3}") int partitions,
            @Value("${campaign.messaging.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(dlt).partitions(partitions).replicas(replicas).build());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> campaignListenerFactory(
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<Object, Object> template,
            @Value("${campaign.messaging.dead-letter-topic}") String dlt) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(dlt, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumers);
        // Commit only after the listener's database transaction has returned successfully.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L)));
        return factory;
    }

    @Bean
    Declarables deliveryTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var email = QueueBuilder.durable(EMAIL_QUEUE).build();
        var sms = QueueBuilder.durable(SMS_QUEUE).build();
        return new Declarables(exchange, email, sms,
                BindingBuilder.bind(email).to(exchange).with(EMAIL_KEY),
                BindingBuilder.bind(sms).to(exchange).with(SMS_KEY));
    }
}

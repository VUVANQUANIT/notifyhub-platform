package com.vuvanquan.notifyhub.reporting.projection;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(name = "reporting.messaging.enabled", havingValue = "true")
public class ReportMessagingConfiguration {
    @Bean KafkaAdmin.NewTopics reportTopics(
            @Value("${reporting.messaging.result-topic}") String results,
            @Value("${reporting.messaging.campaign-topic}") String campaigns,
            @Value("${reporting.messaging.partitions:3}") int partitions,
            @Value("${reporting.messaging.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(results).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(campaigns).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(results + ".reporting.dlt").partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(campaigns + ".reporting.dlt").partitions(partitions).replicas(replicas).build());
    }
    @Bean ConcurrentKafkaListenerContainerFactory<Object, Object> reportResultFactory(
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<Object, Object> template,
            @Value("${reporting.messaging.result-topic}") String topic) { return factory(consumers, template, topic); }
    @Bean ConcurrentKafkaListenerContainerFactory<Object, Object> reportCampaignFactory(
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<Object, Object> template,
            @Value("${reporting.messaging.campaign-topic}") String topic) { return factory(consumers, template, topic); }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory(
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<Object, Object> template, String topic) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, error) -> new TopicPartition(topic + ".reporting.dlt", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L)));
        return factory;
    }
}

package com.vuvanquan.notifyhub.notification.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notification.events.enabled", havingValue = "true")
public class ResultEventConfiguration {
    @Bean KafkaAdmin.NewTopics resultTopics(
            @Value("${notification.events.topic:notifyhub.notification.events.v1}") String topic,
            @Value("${notification.events.partitions:3}") int partitions,
            @Value("${notification.events.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
    }
}

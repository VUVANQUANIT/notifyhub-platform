package com.vuvanquan.notifyhub.campaign.messaging;

import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class DeliveryResultListener {
    private final ObjectMapper json;
    private final DeliveryResultInbox inbox;
    public DeliveryResultListener(ObjectMapper json, DeliveryResultInbox inbox) { this.json = json; this.inbox = inbox; }

    @KafkaListener(id = "campaign-results", topics = "${campaign.messaging.result-topic:notifyhub.notification.events.v1}",
            groupId = "campaign-results-v1", containerFactory = "resultListenerFactory")
    public void receive(String value) { inbox.accept(json.readValue(value, DeliveryResultEvent.class)); }
}

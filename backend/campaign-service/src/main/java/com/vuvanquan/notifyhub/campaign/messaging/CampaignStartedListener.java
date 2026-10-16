package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "campaign.messaging.enabled", havingValue = "true")
public class CampaignStartedListener {
    private final ObjectMapper json;
    private final DispatchInbox inbox;

    public CampaignStartedListener(ObjectMapper json, DispatchInbox inbox) {
        this.json = json; this.inbox = inbox;
    }

    @KafkaListener(id = "campaign-dispatcher", topics = "${campaign.messaging.topic}",
            groupId = "${spring.kafka.consumer.group-id}", containerFactory = "campaignListenerFactory")
    public void receive(String value) {
        EventEnvelope event = json.readValue(value, EventEnvelope.class);
        if (event.eventType().equals("CampaignStarted")) inbox.accept(event);
    }
}

package com.vuvanquan.notifyhub.reporting.projection;

import com.vuvanquan.notifyhub.contracts.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "reporting.messaging.enabled", havingValue = "true")
public class ReportListeners {
    private final ObjectMapper json;
    private final ReportProjection projection;
    public ReportListeners(ObjectMapper json, ReportProjection projection) { this.json = json; this.projection = projection; }
    @KafkaListener(id = "reporting-deliveries", topics = "${reporting.messaging.result-topic}",
            groupId = "reporting-deliveries-v1", containerFactory = "reportResultFactory")
    public void delivery(String value) { projection.delivery(json.readValue(value, DeliveryResultEvent.class)); }

    @KafkaListener(id = "reporting-campaigns", topics = "${reporting.messaging.campaign-topic}",
            groupId = "reporting-campaigns-v1", containerFactory = "reportCampaignFactory")
    public void campaign(String value) {
        String type = json.readTree(value).path("eventType").asString();
        if (Set.of("CampaignStarted", "CampaignCompleted", "CampaignFailed").contains(type)) {
            projection.progress(json.readValue(value, CampaignProgressEvent.class));
        }
    }
}

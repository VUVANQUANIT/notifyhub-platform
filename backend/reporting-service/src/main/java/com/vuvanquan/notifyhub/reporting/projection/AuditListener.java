package com.vuvanquan.notifyhub.reporting.projection;

import com.vuvanquan.notifyhub.contracts.IdentityAuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component @ConditionalOnProperty(name="reporting.audit.enabled",havingValue="true")
public class AuditListener {
    private final ObjectMapper json;private final AuditProjection projection;
    public AuditListener(ObjectMapper json,AuditProjection projection){this.json=json;this.projection=projection;}
    @KafkaListener(id="reporting-audit",topics="${reporting.audit.topic:notifyhub.identity.audit.v1}",groupId="reporting-audit-v1",containerFactory="reportAuditFactory")
    public void accept(String value){projection.apply(json.readValue(value,IdentityAuditEvent.class));}
}

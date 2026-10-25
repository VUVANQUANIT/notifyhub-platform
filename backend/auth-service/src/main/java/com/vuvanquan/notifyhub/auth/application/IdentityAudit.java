package com.vuvanquan.notifyhub.auth.application;

import com.vuvanquan.notifyhub.contracts.IdentityAuditEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
public class IdentityAudit {
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;
    public IdentityAudit(JdbcTemplate db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(UUID tenant,UUID actor,UUID subject,String action){
        var event=new IdentityAuditEvent(UUID.randomUUID(),1,action,tenant,actor,subject,clock.instant());event.validate();
        db.update("INSERT INTO auth.audit_outbox(event_id,tenant_id,envelope,occurred_at) VALUES (?,?,?,?)",event.eventId(),tenant,json.writeValueAsString(event),Timestamp.from(event.occurredAt()));
    }
}

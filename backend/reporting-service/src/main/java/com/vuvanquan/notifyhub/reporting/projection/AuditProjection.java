package com.vuvanquan.notifyhub.reporting.projection;

import com.vuvanquan.notifyhub.contracts.IdentityAuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditProjection {
    private final JdbcTemplate db;private final ObjectMapper json;
    public AuditProjection(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional public void apply(IdentityAuditEvent event){
        event.validate();String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        db.update("INSERT INTO reporting.identity_audit(event_id,tenant_id,actor_id,subject_id,action,occurred_at,payload_hash) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",event.eventId(),event.tenantId(),event.actorId(),event.subjectId(),event.action(),Timestamp.from(event.occurredAt()),hash);
        if(!hash.equals(db.queryForObject("SELECT payload_hash FROM reporting.identity_audit WHERE event_id=?",String.class,event.eventId())))throw new IllegalArgumentException("Audit event identity conflict");
    }
}

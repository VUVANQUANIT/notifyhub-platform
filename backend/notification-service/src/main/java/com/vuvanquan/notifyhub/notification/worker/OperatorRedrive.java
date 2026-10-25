package com.vuvanquan.notifyhub.notification.worker;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Explicit one-off recovery delivery. Original campaign outcome and terminal result stay immutable. */
@Service @ConditionalOnProperty(name="notification.worker.enabled",havingValue="true")
public class OperatorRedrive {
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;
    public OperatorRedrive(JdbcTemplate db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    public List<Map<String,Object>> failures(UUID tenant,int page){
        if(page<0 || page>1000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return db.queryForList("SELECT d.notification_id,d.campaign_id,d.recipient_id,d.channel,d.attempts,d.last_error,d.completed_at,r.notification_id AS recovery_notification_id FROM notification.deliveries d LEFT JOIN notification.redrive_requests r ON r.parent_id=d.notification_id WHERE d.tenant_id=? AND d.status='FAILED' AND d.redrive_parent_id IS NULL ORDER BY d.completed_at DESC,d.notification_id LIMIT 50 OFFSET ?",tenant,(long)page*50);
    }
    public List<Map<String,Object>> history(UUID tenant){return db.queryForList("SELECT r.request_id,r.actor_id,r.parent_id,r.notification_id,r.reason,r.created_at,d.status,d.attempts,d.last_error,d.completed_at FROM notification.redrive_requests r JOIN notification.deliveries d ON d.notification_id=r.notification_id WHERE r.tenant_id=? ORDER BY r.created_at DESC LIMIT 100",tenant);}
    @Transactional public UUID redrive(UUID tenant,UUID actor,UUID parent,UUID request,String reason){
        if(request==null || reason==null || reason.strip().length()<5 || reason.length()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A request ID and recovery reason are required");
        db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->(Object)null,tenant+":"+request);
        // Lock the original so independent request IDs cannot schedule multiple recovery sends.
        var originals=db.queryForList("SELECT * FROM notification.deliveries WHERE tenant_id=? AND notification_id=? AND redrive_parent_id IS NULL FOR UPDATE",tenant,parent);
        if(originals.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var previous=db.queryForList("SELECT parent_id,notification_id,reason FROM notification.redrive_requests WHERE tenant_id=? AND request_id=?",tenant,request);
        if(!previous.isEmpty()){
            var row=previous.getFirst();if(!parent.equals(row.get("parent_id")) || !reason.strip().equals(row.get("reason")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request ID was used for different recovery data");
            return (UUID)row.get("notification_id");
        }
        if(!"FAILED".equals(originals.getFirst().get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only terminal failed deliveries can be redriven");
        if(db.queryForObject("SELECT count(*) FROM notification.redrive_requests WHERE parent_id=?",Integer.class,parent)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Recovery already scheduled");
        var dead=db.queryForList("SELECT payload FROM notification.delivery_handoffs WHERE notification_id=? AND dedupe_key=?",parent,"failed:"+parent);
        if(dead.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"No canonical dead-letter payload");
        var original=json.readValue((byte[])dead.getFirst().get("payload"),SendNotificationTask.class);original.validate(original.channel());
        if(!tenant.equals(original.tenantId()) || !parent.equals(original.notificationId()) || !PayloadIdentity.hash(json.writeValueAsBytes(original)).equals(originals.getFirst().get("payload_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Dead-letter identity conflict");
        UUID id=UUID.randomUUID();var now=DeliveryTime.now(clock);
        var recovery=new SendNotificationTask(id,original.taskVersion(),tenant,original.campaignId(),original.recipientId(),original.correlationId(),original.channel(),original.destination(),original.subject(),original.body(),now);
        byte[] payload=json.writeValueAsBytes(recovery);
        db.update("INSERT INTO notification.deliveries(notification_id,tenant_id,campaign_id,recipient_id,correlation_id,channel,payload_hash,status,created_at,updated_at,redrive_parent_id) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?)",id,tenant,recovery.campaignId(),recovery.recipientId(),recovery.correlationId(),recovery.channel(),PayloadIdentity.hash(payload),Timestamp.from(now),Timestamp.from(now),parent);
        db.update("INSERT INTO notification.redrive_requests(tenant_id,request_id,actor_id,parent_id,notification_id,reason,created_at) VALUES (?,?,?,?,?,?,?)",tenant,request,actor,parent,id,reason.strip(),Timestamp.from(now));
        db.update("INSERT INTO notification.delivery_handoffs(id,dedupe_key,notification_id,exchange_name,routing_key,payload,attempt_number,failure_type,available_at) VALUES (?,?,?,?,?,?,0,'OperatorRedrive',?)",UUID.randomUUID(),"redrive:"+id,id,WorkerConfiguration.EXCHANGE,recovery.channel().equals("EMAIL")?WorkerConfiguration.EMAIL_KEY:WorkerConfiguration.SMS_KEY,payload,Timestamp.from(now));
        return id;
    }
}

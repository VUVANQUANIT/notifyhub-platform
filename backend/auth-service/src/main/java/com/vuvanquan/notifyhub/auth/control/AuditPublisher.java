package com.vuvanquan.notifyhub.auth.control;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component @ConditionalOnProperty(name="auth.audit.publish-enabled",havingValue="true")
public class AuditPublisher {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final KafkaTemplate<Object,Object> kafka;private final String topic;private final Clock clock;
    public AuditPublisher(JdbcTemplate db,PlatformTransactionManager manager,KafkaTemplate<Object,Object> kafka,@Value("${auth.audit.topic:notifyhub.identity.audit.v1}")String topic,Clock clock){this.db=db;this.tx=new TransactionTemplate(manager);this.kafka=kafka;this.topic=topic;this.clock=clock;}
    @Bean KafkaAdmin.NewTopics identityTopics(@Value("${auth.audit.topic:notifyhub.identity.audit.v1}")String topic,@Value("${auth.audit.replicas:1}")int replicas){return new KafkaAdmin.NewTopics(TopicBuilder.name(topic).partitions(3).replicas(replicas).build());}
    @Scheduled(fixedDelayString="${auth.audit.poll-delay-ms:1000}")
    public void poll(){try{for(int i=0;i<20 && publishOne();i++){}}catch(org.springframework.dao.DataAccessException unavailable){/* committed audit remains pending */}}
    public boolean publishOne(){return Boolean.TRUE.equals(tx.execute(status->{
        var rows=db.queryForList("SELECT * FROM auth.audit_outbox WHERE published_at IS NULL AND available_at<=? ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",Timestamp.from(clock.instant()));
        if(rows.isEmpty())return false;var row=rows.getFirst();UUID id=(UUID)row.get("event_id");int attempts=((Number)row.get("attempts")).intValue();
        try{kafka.send(topic,row.get("tenant_id").toString(),row.get("envelope")).get(10,TimeUnit.SECONDS);db.update("UPDATE auth.audit_outbox SET published_at=?,attempts=attempts+1 WHERE event_id=?",Timestamp.from(clock.instant()),id);}
        catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();db.update("UPDATE auth.audit_outbox SET attempts=attempts+1,available_at=? WHERE event_id=?",Timestamp.from(clock.instant().plusSeconds(Math.min(60,1L<<Math.min(attempts,6)))),id);}
        return true;
    }));}
}

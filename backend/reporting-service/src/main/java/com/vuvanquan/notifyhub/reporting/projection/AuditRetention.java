package com.vuvanquan.notifyhub.reporting.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @EnableScheduling
public class AuditRetention {
    private final JdbcTemplate db;
    public AuditRetention(JdbcTemplate db){this.db=db;}
    @Scheduled(fixedDelayString="${reporting.audit.retention-interval-ms:3600000}")
    public void clean(){try{db.update("DELETE FROM reporting.identity_audit WHERE event_id IN (SELECT event_id FROM reporting.identity_audit WHERE occurred_at<now()-interval '90 days' ORDER BY occurred_at LIMIT 500)");}catch(org.springframework.dao.DataAccessException unavailable){/* bounded cleanup retries next tick */}}
}

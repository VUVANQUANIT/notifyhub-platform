package com.vuvanquan.notifyhub.auth.control;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded expiry cleanup. Consumed refresh hashes survive through the absolute session expiry. */
@Component
public class IdentityRetention {
    private final JdbcTemplate db;private final Clock clock;private final boolean enabled;
    public IdentityRetention(JdbcTemplate db,Clock clock,@Value("${auth.retention.enabled:true}")boolean enabled){this.db=db;this.clock=clock;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${auth.retention.interval-ms:3600000}")
    public void poll(){if(enabled)try{clean();}catch(org.springframework.dao.DataAccessException unavailable){/* Retry next tick. */}}
    public void clean(){
        // Each bounded statement commits separately; never hold outbox locks while waiting for a user lock.
            Timestamp now=Timestamp.from(clock.instant()),cutoff=Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
            db.update("DELETE FROM auth.refresh_sessions WHERE id IN (SELECT id FROM auth.refresh_sessions WHERE expires_at<? ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED)",now);
            db.update("UPDATE auth.recovery_mail_outbox SET state='EXPIRED',encrypted_code=NULL,updated_at=? WHERE kind='CODE' AND state='PENDING' AND challenge_id IN (SELECT id FROM auth.recovery_challenges WHERE expires_at<? ORDER BY expires_at LIMIT 500)",now,now);
            db.update("UPDATE auth.invitation_mail_outbox SET state='EXPIRED',encrypted_token=NULL,updated_at=? WHERE state='PENDING' AND invitation_id IN (SELECT id FROM auth.invitations WHERE expires_at<? ORDER BY expires_at LIMIT 500)",now,now);
            db.update("DELETE FROM auth.recovery_challenges c WHERE c.id IN (SELECT id FROM auth.recovery_challenges WHERE expires_at<? AND NOT EXISTS (SELECT 1 FROM auth.recovery_mail_outbox o WHERE o.challenge_id=auth.recovery_challenges.id AND o.state='PENDING') ORDER BY expires_at LIMIT 500)",cutoff);
            db.update("DELETE FROM auth.invitations WHERE id IN (SELECT id FROM auth.invitations WHERE expires_at<? ORDER BY expires_at LIMIT 500)",cutoff);
            db.update("UPDATE auth.users SET mfa_pending_secret=NULL,mfa_pending_expires_at=NULL WHERE id IN (SELECT id FROM auth.users WHERE mfa_pending_expires_at<? ORDER BY mfa_pending_expires_at LIMIT 500)",now);
            db.update("DELETE FROM auth.audit_outbox WHERE event_id IN (SELECT event_id FROM auth.audit_outbox WHERE published_at IS NOT NULL AND occurred_at<? ORDER BY occurred_at LIMIT 500)",Timestamp.from(clock.instant().minus(Duration.ofDays(90))));
    }
}

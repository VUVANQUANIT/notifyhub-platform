package com.vuvanquan.notifyhub.auth.control;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RecoveryMailPublisher {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JavaMailSender mail;
    private final ControlCrypto crypto;
    private final ControlProperties config;
    private final Clock clock;
    private final String from;
    private final boolean polling;
    private final RedisControls controls;
    public RecoveryMailPublisher(JdbcTemplate db, PlatformTransactionManager manager, JavaMailSender mail,
            ControlCrypto crypto, ControlProperties config, Clock clock,
            @Value("${auth.control.mail-from}") String from, @Value("${auth.control.mail-poll-enabled:true}") boolean polling, RedisControls controls) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.mail = mail;
        this.crypto = crypto; this.config = config; this.clock = clock; this.from = from; this.polling = polling; this.controls = controls;
    }
    @Scheduled(fixedDelayString="${auth.control.mail-poll-delay:1000}")
    public void poll() {
        if (!polling) return;
        try { for (int i=0; i<10 && publishOne(); i++) { /* Bound work in one scheduling tick. */ } }
        catch (org.springframework.dao.DataAccessException unavailable) { /* Pending rows survive; retry next tick. */ }
        catch (ControlFailure unavailable) { /* Redis proof is required before sending; retry next tick. */ }
    }
    public boolean publishOne() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            var pending = db.query("SELECT challenge_id,kind FROM auth.recovery_mail_outbox WHERE state='PENDING' AND available_at<=? ORDER BY available_at,challenge_id,kind LIMIT 1 FOR UPDATE SKIP LOCKED", (rs, row) -> new MailId(rs.getObject(1, UUID.class),rs.getString(2)), Timestamp.from(now));
            if (pending.isEmpty()) return false;
            UUID id = pending.getFirst().id(); String kind = pending.getFirst().kind();
            var row = db.queryForMap("SELECT o.encrypted_code,o.attempts,c.expires_at,c.consumed_at,c.recovery_version,u.recovery_version AS current_version,u.email,u.enabled,t.enabled AS tenant_enabled FROM auth.recovery_mail_outbox o JOIN auth.recovery_challenges c ON c.id=o.challenge_id JOIN auth.users u ON u.tenant_id=c.tenant_id AND u.id=c.user_id JOIN auth.tenants t ON t.id=c.tenant_id WHERE o.challenge_id=? AND o.kind=?", id,kind);
            Instant expiry = ((Timestamp) row.get("expires_at")).toInstant();
            if (kind.equals("CODE") && (!expiry.isAfter(now) || row.get("consumed_at") != null || !Boolean.TRUE.equals(row.get("enabled"))
                    || !Boolean.TRUE.equals(row.get("tenant_enabled")) || !row.get("recovery_version").equals(row.get("current_version")) || !controls.active(id))) {
                finish(id, kind, "EXPIRED", "ChallengeExpired", now); return true;
            }
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            var message = new SimpleMailMessage(); message.setFrom(from); message.setTo((String) row.get("email"));
            if (kind.equals("CODE")) {
                String code;
                try { code = crypto.decrypt((String) row.get("encrypted_code")); }
                catch (IllegalStateException invalid) { finish(id, kind, "FAILED", "InvalidEncryptedPayload", now); return true; }
                message.setSubject("NotifyHub password recovery");
                message.setText("Your password recovery code is: " + code + "\nChallenge: " + id + "\nExpires at: " + expiry
                        + "\nOnly the newest code is valid. If you did not request this, ignore this email.");
            } else {
                message.setSubject("NotifyHub password changed");
                message.setText("Your NotifyHub password was changed. Existing refresh sessions have been revoked.\nRecovery: " + id
                        + "\nSign in using your new password. If this was not you, recover your account and contact your tenant administrator.");
            }
            try {
                mail.send(message);
                db.update("UPDATE auth.recovery_mail_outbox SET attempts=? WHERE challenge_id=? AND kind=?", attempts, id, kind);
                finish(id, kind, "SENT", null, clock.instant());
            } catch (MailException unavailable) {
                db.update("UPDATE auth.recovery_mail_outbox SET attempts=? WHERE challenge_id=? AND kind=?", attempts, id, kind);
                if (attempts >= config.otp().mailAttempts()) finish(id, kind, "FAILED", "SmtpUnavailable", clock.instant());
                else db.update("UPDATE auth.recovery_mail_outbox SET available_at=?,failure_code='SmtpUnavailable',updated_at=? WHERE challenge_id=? AND kind=?",
                        Timestamp.from(now.plusSeconds(1L << (attempts-1))), Timestamp.from(now), id, kind);
            }
            return true;
        }));
    }
    private void finish(UUID id, String kind, String state, String failure, Instant now) {
        db.update("UPDATE auth.recovery_mail_outbox SET state=?,encrypted_code=NULL,failure_code=?,updated_at=? WHERE challenge_id=? AND kind=?", state, failure, Timestamp.from(now), id, kind);
    }
    private record MailId(UUID id, String kind) {}
}

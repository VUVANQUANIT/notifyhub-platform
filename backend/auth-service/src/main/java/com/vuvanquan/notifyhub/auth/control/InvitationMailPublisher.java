package com.vuvanquan.notifyhub.auth.control;

import com.vuvanquan.notifyhub.auth.application.MemberInvitations;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class InvitationMailPublisher {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JavaMailSender mail;
    private final ControlCrypto crypto;
    private final MemberInvitations invitations;
    private final Clock clock;
    private final boolean polling;
    private final String from;
    public InvitationMailPublisher(JdbcTemplate db,PlatformTransactionManager manager,JavaMailSender mail,ControlCrypto crypto,
            MemberInvitations invitations,Clock clock,@Value("${auth.control.mail-from}") String from,
            @Value("${auth.control.mail-poll-enabled:true}") boolean polling) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.mail=mail; this.crypto=crypto;
        this.invitations=invitations; this.clock=clock; this.from=from; this.polling=polling;
    }
    @Scheduled(fixedDelayString="${auth.control.mail-poll-delay:1000}")
    public void poll() {
        if (!polling) return;
        try { for(int i=0;i<10 && publishOne();i++) { /* bounded tick */ } }
        catch(org.springframework.dao.DataAccessException unavailable) { /* durable pending rows retry next tick */ }
    }
    public boolean publishOne() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var candidates=db.query("SELECT i.id,i.tenant_id FROM auth.invitation_mail_outbox m JOIN auth.invitations i ON i.id=m.invitation_id WHERE m.state='PENDING' AND m.available_at<=? ORDER BY m.available_at LIMIT 1",
                (rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},Timestamp.from(clock.instant()));
            if(candidates.isEmpty()) return false;
            UUID id=candidates.getFirst()[0], tenant=candidates.getFirst()[1];
            // Same lock order as accept/revoke: tenant -> mail. Recheck after acquiring locks.
            boolean enabled=Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM auth.tenants WHERE id=? FOR UPDATE",Boolean.class,tenant));
            var pending=db.queryForList("SELECT * FROM auth.invitation_mail_outbox WHERE invitation_id=? AND state='PENDING' AND available_at<=? FOR UPDATE SKIP LOCKED",id,Timestamp.from(clock.instant()));
            if(pending.isEmpty()) return false;
            var outbox=pending.getFirst();
            var row=db.queryForMap("SELECT i.*,t.slug FROM auth.invitations i JOIN auth.tenants t ON t.id=i.tenant_id WHERE i.id=?",id);
            if(!enabled || row.get("consumed_at")!=null || row.get("revoked_at")!=null || !((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant())
                || db.queryForObject("SELECT count(*) FROM auth.users WHERE tenant_id=? AND email=?",Integer.class,tenant,row.get("email"))>0) {
                finish(id,"EXPIRED"); return true;
            }
            String token;
            try { token=crypto.decrypt((String)outbox.get("encrypted_token")); }
            catch(IllegalStateException corrupted) { finish(id,"FAILED"); return true; }
            var message=new SimpleMailMessage(); message.setFrom(from); message.setTo((String)row.get("email"));
            message.setSubject("NotifyHub invitation");
            message.setText("You have been invited to NotifyHub.\nTenant: "+row.get("slug")+"\nEmail: "+row.get("email")+"\nSet your own password: "+invitations.link(token)
                +"\nExpires at: "+row.get("expires_at")+"\nThis link can be used once. If you did not expect it, ignore this email.");
            int attempts=((Number)outbox.get("attempts")).intValue()+1;
            db.update("UPDATE auth.invitation_mail_outbox SET attempts=?,updated_at=? WHERE invitation_id=?",attempts,Timestamp.from(clock.instant()),id);
            try { mail.send(message); finish(id,"SENT"); }
            catch(MailException failure) {
                if(attempts>=4) finish(id,"FAILED");
                else db.update("UPDATE auth.invitation_mail_outbox SET available_at=? WHERE invitation_id=?",Timestamp.from(clock.instant().plusSeconds(1L<<(attempts-1))),id);
            }
            return true;
        }));
    }
    private void finish(UUID id,String state) { db.update("UPDATE auth.invitation_mail_outbox SET state=?,encrypted_token=NULL,updated_at=? WHERE invitation_id=?",state,Timestamp.from(clock.instant()),id); }
}

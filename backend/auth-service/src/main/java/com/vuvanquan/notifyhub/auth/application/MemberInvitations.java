package com.vuvanquan.notifyhub.auth.application;

import com.vuvanquan.notifyhub.auth.application.AuthModels.Actor;
import com.vuvanquan.notifyhub.auth.control.ControlCrypto;
import com.vuvanquan.notifyhub.auth.domain.IdentityRules;
import com.vuvanquan.notifyhub.auth.domain.Role;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberInvitations {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    private final ControlCrypto crypto;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String publicUrl;
    private final IdentityAudit audit;
    public MemberInvitations(JdbcTemplate db, PlatformTransactionManager manager, IdentityService identities,
            PasswordEncoder passwords, ControlCrypto crypto, Clock clock,
            @Value("${auth.invitation.public-url:http://localhost:4200}") String publicUrl, IdentityAudit audit) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.identities=identities;
        this.passwords=passwords; this.crypto=crypto; this.clock=clock;
        this.audit=audit;
        URI uri=URI.create(publicUrl);
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getScheme().equals("https") || (uri.getScheme().equals("http") && Set.of("localhost","127.0.0.1").contains(uri.getHost()))))
            throw new IllegalArgumentException("Invitation public URL requires HTTPS (HTTP loopback only for local development)");
        this.publicUrl=publicUrl.replaceAll("/+$", "");
    }
    public record Invitation(UUID id, String email, String displayName, String role, Instant expiresAt, String state, String mailState) {}
    public List<Invitation> list(Actor actor) {
        requireAdmin(actor);
        return db.query("SELECT i.*,m.state AS mail_state FROM auth.invitations i JOIN auth.invitation_mail_outbox m ON m.invitation_id=i.id WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
                (rs,n) -> new Invitation(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("role"),rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("consumed_at")!=null?"ACCEPTED":rs.getTimestamp("revoked_at")!=null?"REVOKED":!rs.getTimestamp("expires_at").toInstant().isAfter(clock.instant())?"EXPIRED":"PENDING",rs.getString("mail_state")),actor.tenantId());
    }
    public UUID invite(Actor actor, String email, String name, Role role) {
        String normalized=IdentityRules.email(email), displayName=IdentityRules.name(name);
        if (role==null) throw new IllegalArgumentException("Role is required");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return tx.execute(status -> {
            lockTenant(actor.tenantId()); requireAdmin(actor);
            if (db.queryForObject("SELECT count(*) FROM auth.users WHERE tenant_id=? AND email=?",Integer.class,actor.tenantId(),normalized)>0)
                throw new AuthFailure(HttpStatus.CONFLICT,"Email already exists in tenant");
            // Reissuing invalidates all earlier links, including an email already accepted by SMTP.
            db.update("UPDATE auth.invitations SET revoked_at=? WHERE tenant_id=? AND email=? AND consumed_at IS NULL AND revoked_at IS NULL",Timestamp.from(clock.instant()),actor.tenantId(),normalized);
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO auth.invitations(id,tenant_id,email,display_name,role,token_hash,expires_at) VALUES (?,?,?,?,?,?,?)",id,actor.tenantId(),normalized,displayName,role.name(),TokenIssuer.hash(token),Timestamp.from(clock.instant().plus(Duration.ofHours(24))));
            db.update("INSERT INTO auth.invitation_mail_outbox(invitation_id,encrypted_token) VALUES (?,?)",id,crypto.encrypt(token));
            audit.record(actor.tenantId(),actor.userId(),id,"INVITE_ISSUED");
            return id;
        });
    }
    public void revoke(Actor actor, UUID id) {
        tx.executeWithoutResult(status -> {
            lockTenant(actor.tenantId()); requireAdmin(actor);
            if (db.update("UPDATE auth.invitations SET revoked_at=coalesce(revoked_at,?) WHERE tenant_id=? AND id=? AND consumed_at IS NULL",Timestamp.from(clock.instant()),actor.tenantId(),id)==0)
                throw new AuthFailure(HttpStatus.NOT_FOUND,"Pending invitation not found");
            audit.record(actor.tenantId(),actor.userId(),id,"INVITE_REVOKED");
        });
    }
    public void accept(String token, String password) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw AuthFailure.unauthorized();
        String hash=TokenIssuer.hash(token), passwordHash=passwords.encode(IdentityRules.password(password));
        tx.executeWithoutResult(status -> {
            var tenants=db.query("SELECT tenant_id FROM auth.invitations WHERE token_hash=?",(rs,n)->rs.getObject(1,UUID.class),hash);
            if (tenants.isEmpty()) throw AuthFailure.unauthorized();
            UUID tenant=tenants.getFirst(); lockTenant(tenant);
            var invitation=db.queryForMap("SELECT * FROM auth.invitations WHERE token_hash=? FOR UPDATE",hash);
            if (invitation.get("consumed_at")!=null || invitation.get("revoked_at")!=null
                    || !((Timestamp)invitation.get("expires_at")).toInstant().isAfter(clock.instant())) throw AuthFailure.unauthorized();
            if (db.queryForObject("SELECT count(*) FROM auth.users WHERE tenant_id=? AND email=?",Integer.class,tenant,invitation.get("email"))>0)
                throw AuthFailure.unauthorized();
            UUID newUser=UUID.randomUUID();
            db.update("INSERT INTO auth.users(id,tenant_id,email,display_name,password_hash,role,email_verified_at) VALUES (?,?,?,?,?,?,?)",newUser,tenant,invitation.get("email"),invitation.get("display_name"),passwordHash,invitation.get("role"),Timestamp.from(clock.instant()));
            audit.record(tenant,newUser,(UUID)invitation.get("id"),"INVITE_ACCEPTED");
            db.update("UPDATE auth.invitations SET consumed_at=? WHERE id=?",Timestamp.from(clock.instant()),invitation.get("id"));
            // Publisher also locks tenant first, preventing accept/revoke from racing an SMTP send.
            db.update("UPDATE auth.invitation_mail_outbox SET encrypted_token=NULL,state=CASE WHEN state='PENDING' THEN 'EXPIRED' ELSE state END,updated_at=? WHERE invitation_id=?",Timestamp.from(clock.instant()),invitation.get("id"));
        });
    }
    public String link(String token) { return publicUrl+"/activate#"+token; }
    private void requireAdmin(Actor actor) { if (identities.me(actor).role()!=Role.ADMIN) throw AuthFailure.forbidden(); }
    private void lockTenant(UUID tenant) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM auth.tenants WHERE id=? FOR UPDATE",Boolean.class,tenant))) throw AuthFailure.unauthorized();
    }
}

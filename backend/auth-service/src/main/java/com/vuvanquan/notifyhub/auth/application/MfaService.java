package com.vuvanquan.notifyhub.auth.application;

import com.vuvanquan.notifyhub.auth.application.AuthModels.Actor;
import com.vuvanquan.notifyhub.auth.control.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MfaService {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final ControlCrypto crypto;
    private final PasswordEncoder passwords; private final RedisControls controls; private final Clock clock;
    private final IdentityAudit audit;
    public MfaService(JdbcTemplate db,PlatformTransactionManager manager,ControlCrypto crypto,PasswordEncoder passwords,RedisControls controls,Clock clock,IdentityAudit audit){
        this.db=db;this.tx=new TransactionTemplate(manager);this.crypto=crypto;this.passwords=passwords;this.controls=controls;this.clock=clock;
        this.audit=audit;
    }
    public record Enrollment(String secret,String uri,Instant expiresAt) {}
    public boolean enabled(Actor actor){var rows=db.query("SELECT mfa_secret IS NOT NULL FROM auth.users u JOIN auth.tenants t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=? AND u.enabled AND t.enabled",(rs,n)->rs.getBoolean(1),actor.tenantId(),actor.userId());if(rows.isEmpty())throw AuthFailure.unauthorized();return rows.getFirst();}
    public Enrollment begin(Actor actor,String password){
        rate(actor);
        return tx.execute(status->{
            var row=lockUser(actor); requirePassword(row,password);
            if(row.get("mfa_secret")!=null)throw new AuthFailure(HttpStatus.CONFLICT,"MFA is already enabled");
            String secret=Totp.secret();Instant expiry=clock.instant().plus(Duration.ofMinutes(10));
            db.update("UPDATE auth.users SET mfa_pending_secret=?,mfa_pending_expires_at=? WHERE tenant_id=? AND id=?",crypto.encrypt(secret),Timestamp.from(expiry),actor.tenantId(),actor.userId());
            audit.record(actor.tenantId(),actor.userId(),actor.userId(),"MFA_ENROLLMENT");
            String label=URLEncoder.encode("NotifyHub:"+row.get("slug")+":"+row.get("email"),StandardCharsets.UTF_8).replace("+","%20");
            return new Enrollment(secret,"otpauth://totp/"+label+"?secret="+secret+"&issuer=NotifyHub&algorithm=SHA1&digits=6&period=30",expiry);
        });
    }
    public List<String> confirm(Actor actor,String code){
        rate(actor);
        return tx.execute(status->{
            var row=lockUser(actor);
            if(row.get("mfa_secret")!=null || row.get("mfa_pending_secret")==null || row.get("mfa_pending_expires_at")==null || !((Timestamp)row.get("mfa_pending_expires_at")).toInstant().isAfter(clock.instant()))throw AuthFailure.unauthorized();
            String secret=crypto.decrypt((String)row.get("mfa_pending_secret"));
            long step=Totp.verify(secret,code,clock.instant().getEpochSecond()/30,-1);if(step<0)throw AuthFailure.unauthorized();
            db.update("UPDATE auth.users SET mfa_secret=mfa_pending_secret,mfa_pending_secret=NULL,mfa_pending_expires_at=NULL,mfa_last_step=? WHERE tenant_id=? AND id=?",step,actor.tenantId(),actor.userId());
            db.update("DELETE FROM auth.mfa_recovery_codes WHERE tenant_id=? AND user_id=?",actor.tenantId(),actor.userId());
            var recovery=new ArrayList<String>();var random=new SecureRandom();
            for(int i=0;i<8;i++){byte[] bytes=new byte[32];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);recovery.add(raw);db.update("INSERT INTO auth.mfa_recovery_codes(tenant_id,user_id,code_hash) VALUES (?,?,?)",actor.tenantId(),actor.userId(),TokenIssuer.hash(raw));}
            revoke(actor);audit.record(actor.tenantId(),actor.userId(),actor.userId(),"MFA_ENABLED");return List.copyOf(recovery);
        });
    }
    public void disable(Actor actor,String password,String code){
        rate(actor);
        tx.executeWithoutResult(status->{var row=lockUser(actor);requirePassword(row,password);if(row.get("mfa_secret")==null)throw AuthFailure.unauthorized();verifyLocked(actor,row,code);
            db.update("UPDATE auth.users SET mfa_secret=NULL,mfa_pending_secret=NULL,mfa_pending_expires_at=NULL,mfa_last_step=-1 WHERE tenant_id=? AND id=?",actor.tenantId(),actor.userId());
            db.update("DELETE FROM auth.mfa_recovery_codes WHERE tenant_id=? AND user_id=?",actor.tenantId(),actor.userId());revoke(actor);
            audit.record(actor.tenantId(),actor.userId(),actor.userId(),"MFA_DISABLED");
        });
    }
    // Called only after validating password and locking tenant in the login transaction.
    void verifyLogin(UUID tenant,UUID user,String code){var actor=new Actor(tenant,user);var row=lockUser(actor);if(row.get("mfa_secret")!=null)verifyLocked(actor,row,code);}
    private void verifyLocked(Actor actor,Map<String,Object> row,String code){
        if(code!=null && code.matches("[A-Za-z0-9_-]{43}")){
            if(db.update("DELETE FROM auth.mfa_recovery_codes WHERE tenant_id=? AND user_id=? AND code_hash=?",actor.tenantId(),actor.userId(),TokenIssuer.hash(code))==1)return;
            throw AuthFailure.unauthorized();
        }
        long step=Totp.verify(crypto.decrypt((String)row.get("mfa_secret")),code,clock.instant().getEpochSecond()/30,((Number)row.get("mfa_last_step")).longValue());
        if(step<0)throw AuthFailure.unauthorized();
        db.update("UPDATE auth.users SET mfa_last_step=? WHERE tenant_id=? AND id=?",step,actor.tenantId(),actor.userId());
    }
    private Map<String,Object> lockUser(Actor actor){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM auth.tenants WHERE id=? FOR UPDATE",Boolean.class,actor.tenantId())))throw AuthFailure.unauthorized();
        var rows=db.queryForList("SELECT u.*,t.slug FROM auth.users u JOIN auth.tenants t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=? AND u.enabled FOR UPDATE OF u",actor.tenantId(),actor.userId());
        if(rows.isEmpty())throw AuthFailure.unauthorized();return rows.getFirst();
    }
    private void requirePassword(Map<String,Object> row,String password){if(password==null || password.length()>128 || !passwords.matches(password,(String)row.get("password_hash")))throw AuthFailure.unauthorized();}
    private void rate(Actor actor){controls.rate("mfa-user",actor.tenantId()+":"+actor.userId(),new ControlProperties.Rate(10,Duration.ofMinutes(5)));}
    private void revoke(Actor actor){db.update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),actor.tenantId(),actor.userId());}
}

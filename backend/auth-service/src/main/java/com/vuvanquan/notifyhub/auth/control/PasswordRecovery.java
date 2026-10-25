package com.vuvanquan.notifyhub.auth.control;

import com.vuvanquan.notifyhub.auth.application.AuthFailure;
import com.vuvanquan.notifyhub.auth.domain.IdentityRules;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordRecovery {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RedisControls controls;
    private final ControlProperties config;
    private final ControlCrypto crypto;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public PasswordRecovery(JdbcTemplate db, PlatformTransactionManager manager, RedisControls controls,
            ControlProperties config, ControlCrypto crypto, PasswordEncoder passwords, Clock clock) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.controls = controls;
        this.config = config; this.crypto = crypto; this.passwords = passwords; this.clock = clock;
    }

    public record Requested(UUID challengeId, String message) {}
    public Requested request(String slug, String email, String address) {
        String tenantSlug = normalize(slug), normalizedEmail = normalize(email);
        String account = account(tenantSlug, normalizedEmail);
        controls.rate("otp-ip", address, config.otp().ipRequests());
        controls.rate("otp-account", account, config.otp().requests());
        controls.rate("otp-cooldown", account, new ControlProperties.Rate(1, config.otp().resendCooldown()));
        UUID challenge = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        Instant expires = clock.instant().plus(config.otp().ttl());
        try {
            tx.executeWithoutResult(status -> {
                var tenants = db.query("SELECT id FROM auth.tenants WHERE slug=? AND enabled FOR UPDATE",
                        (rs, row) -> rs.getObject(1, UUID.class), tenantSlug);
                // Store a decoy for unknown/disabled accounts: the response never exposes account existence.
                controls.issue(challenge, code, config.otp().ttl());
                if (tenants.isEmpty()) return;
                UUID tenant = tenants.getFirst();
                var users = db.query("SELECT id FROM auth.users WHERE tenant_id=? AND email=? AND enabled FOR UPDATE",
                        (rs, row) -> rs.getObject(1, UUID.class), tenant, normalizedEmail);
                if (users.isEmpty()) return;
                UUID user = users.getFirst();
                long version = db.queryForObject("UPDATE auth.users SET recovery_version=recovery_version+1 WHERE tenant_id=? AND id=? RETURNING recovery_version", Long.class, tenant, user);
                db.update("INSERT INTO auth.recovery_challenges(id,tenant_id,user_id,recovery_version,expires_at) VALUES (?,?,?,?,?)", challenge, tenant, user, version, Timestamp.from(expires));
                db.update("INSERT INTO auth.recovery_mail_outbox(challenge_id,kind,encrypted_code,available_at) VALUES (?,'CODE',?,?)", challenge, crypto.encrypt(code), Timestamp.from(clock.instant()));
            });
        } catch (DataAccessException | ControlFailure unavailable) {
            try { controls.delete(challenge); } catch (ControlFailure ignored) { /* Uncommitted decoy expires by TTL. */ }
            throw ControlFailure.unavailable();
        }
        return new Requested(challenge, "If the account is eligible, a recovery code will be emailed");
    }

    public void reset(UUID challenge, String code, String password) {
        String validated = IdentityRules.password(password);
        controls.rate("otp-reset", challenge.toString(), config.reset());
        if (!controls.verify(challenge, code, config.otp().attempts())) throw AuthFailure.unauthorized();
        // Redis verifies guesses; PostgreSQL owns the single-use receipt and password/session transaction.
        // Keep Redis proof until commit so a transient DB failure can be retried with the same OTP.
        String hash = passwords.encode(validated);
        try {
            tx.executeWithoutResult(status -> {
                var found = db.query("SELECT tenant_id FROM auth.recovery_challenges WHERE id=?", (rs, row) -> rs.getObject(1, UUID.class), challenge);
                if (found.isEmpty()) throw AuthFailure.unauthorized();
                UUID tenant = found.getFirst();
                Boolean enabled = db.queryForObject("SELECT enabled FROM auth.tenants WHERE id=? FOR UPDATE", Boolean.class, tenant);
                var state = db.queryForMap("SELECT c.user_id,c.recovery_version,c.expires_at,c.consumed_at,u.enabled,u.recovery_version AS current_version FROM auth.recovery_challenges c JOIN auth.users u ON u.tenant_id=c.tenant_id AND u.id=c.user_id WHERE c.id=? FOR UPDATE OF c,u", challenge);
                Instant now = clock.instant();
                if (!Boolean.TRUE.equals(enabled) || !Boolean.TRUE.equals(state.get("enabled")) || state.get("consumed_at") != null
                        || !((Timestamp) state.get("expires_at")).toInstant().isAfter(now)
                        || !state.get("recovery_version").equals(state.get("current_version"))) throw AuthFailure.unauthorized();
                UUID user = (UUID) state.get("user_id");
                db.update("UPDATE auth.users SET password_hash=?,recovery_version=recovery_version+1 WHERE tenant_id=? AND id=?", hash, tenant, user);
                db.update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL", Timestamp.from(now), tenant, user);
                db.update("UPDATE auth.recovery_challenges SET consumed_at=? WHERE id=?", Timestamp.from(now), challenge);
                db.update("UPDATE auth.recovery_mail_outbox SET encrypted_code=NULL,state=CASE WHEN state='PENDING' THEN 'EXPIRED' ELSE state END,updated_at=? WHERE challenge_id=? AND kind='CODE'", Timestamp.from(now), challenge);
                db.update("INSERT INTO auth.recovery_mail_outbox(challenge_id,kind,available_at) VALUES (?,'NOTICE',?)", challenge, Timestamp.from(now));
            });
        } catch (DataAccessException unavailable) { throw ControlFailure.unavailable(); }
        // Cleanup is best effort AFTER commit. A remaining Redis proof cannot bypass the consumed DB receipt.
        try { controls.delete(challenge); } catch (ControlFailure ignored) { /* Receipt already committed; TTL removes proof. */ }
    }
    public static String account(String slug, String email) { return normalize(slug) + "\0" + normalize(email); }
    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
}

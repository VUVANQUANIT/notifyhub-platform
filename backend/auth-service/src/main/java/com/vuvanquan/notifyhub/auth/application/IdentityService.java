package com.vuvanquan.notifyhub.auth.application;

import static com.vuvanquan.notifyhub.auth.application.AuthModels.*;

import com.vuvanquan.notifyhub.auth.configuration.AuthProperties;
import com.vuvanquan.notifyhub.auth.domain.IdentityRules;
import com.vuvanquan.notifyhub.auth.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final TokenIssuer issuer;
    private final AuthProperties config;
    private final Clock clock;
    private final String dummyHash;

    public IdentityService(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
            PasswordEncoder passwords, TokenIssuer issuer, AuthProperties config, Clock clock) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.passwords = passwords;
        this.issuer = issuer; this.config = config; this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Tokens register(String slug, String name, String email, String displayName, String password) {
        if (!config.registrationEnabled()) throw AuthFailure.forbidden();
        String tenantSlug = IdentityRules.slug(slug), tenantName = IdentityRules.name(name);
        String userEmail = IdentityRules.email(email), userName = IdentityRules.name(displayName);
        String hash = passwords.encode(IdentityRules.password(password));
        try {
            return tx.execute(status -> {
                UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
                db.update("INSERT INTO auth.tenants(id,slug,name) VALUES (?,?,?)", tenant, tenantSlug, tenantName);
                var user = insertUser(tenant, id, userEmail, userName, hash, Role.ADMIN);
                return session(user);
            });
        } catch (DuplicateKeyException conflict) {
            throw new AuthFailure(HttpStatus.CONFLICT, "Tenant slug already exists");
        }
    }

    public Tokens login(String slug, String email, String password) {
        // Login uses the same generic response for unknown, disabled and incorrect credentials.
        if (password == null || password.length() > 128) throw AuthFailure.unauthorized();
        String normalizedSlug = slug == null ? "" : slug.strip().toLowerCase(java.util.Locale.ROOT);
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
        return tx.execute(status -> {
            var tenants = db.query("SELECT id FROM auth.tenants WHERE slug=? AND enabled FOR UPDATE",
                    (rs, row) -> rs.getObject("id", UUID.class), normalizedSlug);
            User user = tenants.isEmpty() ? null : findUserByEmail(tenants.getFirst(), normalizedEmail);
            boolean matches = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
            if (user == null || !user.enabled() || !matches) throw AuthFailure.unauthorized();
            return session(user);
        });
    }

    public Tokens refresh(String rawToken) {
        String hash = TokenIssuer.hash(rawToken);
        // A rejected replay must commit its revocation. Throw only AFTER TransactionTemplate commits.
        Tokens result = tx.execute(status -> {
            var found = db.query("SELECT s.id,s.tenant_id,s.user_id FROM auth.refresh_tokens t JOIN auth.refresh_sessions s ON s.id=t.session_id WHERE t.token_hash=?",
                    (rs, row) -> new SessionId(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class)), hash);
            if (found.isEmpty()) return null;
            var sid = found.getFirst();
            // All identity mutations lock tenant first, then session. This also serializes concurrent refreshes.
            if (!lockTenant(sid.tenant())) return null;
            var user = findUser(sid.tenant(), sid.user());
            var state = db.queryForMap("SELECT s.expires_at,s.revoked_at,t.consumed_at FROM auth.refresh_sessions s JOIN auth.refresh_tokens t ON t.session_id=s.id WHERE s.id=? AND t.token_hash=? FOR UPDATE OF s", sid.id(), hash);
            Instant now = clock.instant();
            Instant expiry = ((Timestamp) state.get("expires_at")).toInstant();
            if (state.get("revoked_at") != null || !expiry.isAfter(now)) return null;
            if (state.get("consumed_at") != null || user == null || !user.enabled()) {
                revoke(sid.id(), now); return null;
            }
            db.update("UPDATE auth.refresh_tokens SET consumed_at=? WHERE token_hash=?", Timestamp.from(now), hash);
            return tokens(user, sid.id(), expiry, now);
        });
        if (result == null) throw AuthFailure.unauthorized();
        return result;
    }

    public void logout(String rawToken) {
        String hash = TokenIssuer.hash(rawToken);
        tx.executeWithoutResult(status -> {
            var found = db.query("SELECT s.id,s.tenant_id FROM auth.refresh_sessions s JOIN auth.refresh_tokens t ON t.session_id=s.id WHERE t.token_hash=?",
                    (rs, row) -> List.of(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)), hash);
            if (!found.isEmpty()) {
                lockTenant(found.getFirst().get(1)); revoke(found.getFirst().getFirst(), clock.instant());
            }
        });
    }

    public UserView me(Actor actor) { return active(actor).view(); }
    public TenantView tenant(Actor actor) {
        active(actor);
        return db.queryForObject("SELECT id,slug,name FROM auth.tenants WHERE id=?",
                (rs, row) -> new TenantView(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")), actor.tenantId());
    }

    public UserPage users(Actor actor, int page, int size) {
        admin(actor);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        var items = db.query("SELECT * FROM auth.users WHERE tenant_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                (rs, row) -> mapUser(rs).view(), actor.tenantId(), size, (long) page * size);
        long count = db.queryForObject("SELECT count(*) FROM auth.users WHERE tenant_id=?", Long.class, actor.tenantId());
        return new UserPage(items, count, page, size);
    }

    public UserView createUser(Actor actor, String email, String displayName, String password, Role role) {
        String normalizedEmail = IdentityRules.email(email), name = IdentityRules.name(displayName);
        String hash = passwords.encode(IdentityRules.password(password));
        if (role == null) throw new IllegalArgumentException("Role is required");
        try {
            return tx.execute(status -> {
                lockTenant(actor.tenantId()); admin(actor);
                return insertUser(actor.tenantId(), UUID.randomUUID(), normalizedEmail, name, hash, role).view();
            });
        } catch (DuplicateKeyException conflict) {
            throw new AuthFailure(HttpStatus.CONFLICT, "Email already exists in tenant");
        }
    }

    public UserView changeRole(Actor actor, UUID userId, Role role) {
        if (role == null) throw new IllegalArgumentException("Role is required");
        return mutate(actor, userId, role, null);
    }
    public UserView changeEnabled(Actor actor, UUID userId, boolean enabled) { return mutate(actor, userId, null, enabled); }

    private UserView mutate(Actor actor, UUID userId, Role role, Boolean enabled) {
        return tx.execute(status -> {
            lockTenant(actor.tenantId()); admin(actor);
            var user = findUser(actor.tenantId(), userId);
            if (user == null) throw new AuthFailure(HttpStatus.NOT_FOUND, "User not found");
            Role nextRole = role == null ? user.role() : role;
            boolean nextEnabled = enabled == null ? user.enabled() : enabled;
            int admins = db.queryForObject("SELECT count(*) FROM auth.users WHERE tenant_id=? AND role='ADMIN' AND enabled", Integer.class, actor.tenantId());
            try { IdentityRules.keepAdmin(user.role(), user.enabled(), nextRole, nextEnabled, admins); }
            catch (IllegalStateException lastAdmin) { throw new AuthFailure(HttpStatus.CONFLICT, lastAdmin.getMessage()); }
            db.update("UPDATE auth.users SET role=?,enabled=? WHERE tenant_id=? AND id=?", nextRole.name(), nextEnabled, actor.tenantId(), userId);
            db.update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL",
                    Timestamp.from(clock.instant()), actor.tenantId(), userId);
            return findUser(actor.tenantId(), userId).view();
        });
    }

    private User active(Actor actor) {
        var user = findUser(actor.tenantId(), actor.userId());
        Boolean enabled = db.query("SELECT enabled FROM auth.tenants WHERE id=?", (rs, row) -> rs.getBoolean(1), actor.tenantId()).stream().findFirst().orElse(false);
        if (user == null || !user.enabled() || !enabled) throw AuthFailure.unauthorized();
        return user;
    }
    private void admin(Actor actor) { if (active(actor).role() != Role.ADMIN) throw AuthFailure.forbidden(); }
    private boolean lockTenant(UUID id) {
        return db.query("SELECT enabled FROM auth.tenants WHERE id=? FOR UPDATE", (rs, row) -> rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }
    private User findUser(UUID tenant, UUID user) {
        return db.query("SELECT * FROM auth.users WHERE tenant_id=? AND id=?", (rs, row) -> mapUser(rs), tenant, user).stream().findFirst().orElse(null);
    }
    private User findUserByEmail(UUID tenant, String email) {
        return db.query("SELECT * FROM auth.users WHERE tenant_id=? AND email=?", (rs, row) -> mapUser(rs), tenant, email).stream().findFirst().orElse(null);
    }
    private User insertUser(UUID tenant, UUID id, String email, String name, String hash, Role role) {
        db.update("INSERT INTO auth.users(id,tenant_id,email,display_name,password_hash,role) VALUES (?,?,?,?,?,?)", id, tenant, email, name, hash, role.name());
        return new User(id, tenant, email, name, hash, role, true);
    }
    private Tokens session(User user) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), expiry = now.plus(config.refreshTtl());
        UUID sessionId = UUID.randomUUID();
        db.update("INSERT INTO auth.refresh_sessions(id,tenant_id,user_id,expires_at) VALUES (?,?,?,?)", sessionId, user.tenantId(), user.id(), Timestamp.from(expiry));
        return tokens(user, sessionId, expiry, now);
    }
    private Tokens tokens(User user, UUID sessionId, Instant expiry, Instant now) {
        String access = issuer.accessToken(user, now), refresh = issuer.refreshToken();
        db.update("INSERT INTO auth.refresh_tokens(token_hash,session_id,expires_at) VALUES (?,?,?)", TokenIssuer.hash(refresh), sessionId, Timestamp.from(expiry));
        return new Tokens("Bearer", access, config.accessTtl().toSeconds(), refresh, expiry);
    }
    private void revoke(UUID session, Instant now) {
        db.update("UPDATE auth.refresh_sessions SET revoked_at=coalesce(revoked_at,?) WHERE id=?", Timestamp.from(now), session);
    }
    private static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("password_hash"), Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"));
    }
    private record SessionId(UUID id, UUID tenant, UUID user) {}
}

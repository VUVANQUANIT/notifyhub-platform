package com.vuvanquan.notifyhub.auth.control;

import static org.assertj.core.api.Assertions.*;
import com.vuvanquan.notifyhub.auth.application.*;
import com.vuvanquan.notifyhub.auth.domain.Role;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"auth.control.mail-poll-enabled=false", "auth.control.requests.limit=1000", "auth.control.requests.window=1s", "auth.control.otp.resend-cooldown=1s"})
class RecoveryIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> MAIL = new GenericContainer<>("mailhog/mailhog:v1.0.1").withExposedPorts(1025,8025);
    static final Path KEYS = tempDirectory();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl); registry.add("spring.datasource.username", DB::getUsername); registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.mail.host", MAIL::getHost); registry.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
        registry.add("auth.signing-key-path", () -> KEYS.resolve("private.pem").toString());
        registry.add("auth.control.secret-path", () -> KEYS.resolve("control.secret").toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisControls controls;
    @Autowired ControlCrypto crypto;
    @Autowired PasswordRecovery recovery;
    @Autowired RecoveryMailPublisher publisher;
    @Autowired IdentityService identities;
    @Autowired JwtDecoder decoder;
    @Autowired JavaMailSenderImpl mail;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();
    static final String OLD = "old long unique passphrase", NEW = "new long unique passphrase";
    AuthModels.Tokens tokens;
    UUID tenant, user;

    @BeforeEach void reset() {
        db.execute("DROP TRIGGER IF EXISTS reject_password ON auth.users");
        db.execute("DROP TRIGGER IF EXISTS reject_outbox ON auth.recovery_mail_outbox");
        db.execute("TRUNCATE auth.tenants CASCADE");
        flushRedis();
        mail.setHost(MAIL.getHost()); mail.setPort(MAIL.getMappedPort(1025));
        tokens = identities.register("tenant-one", "Tenant One", "admin@example.com", "Admin", OLD);
        var jwt = decoder.decode(tokens.accessToken()); tenant=UUID.fromString(jwt.getClaimAsString("tenant_id")); user=UUID.fromString(jwt.getSubject());
    }

    @Test void generic_request_and_encrypted_outbox_deliver_real_code_to_mailhog() throws Exception {
        var known = http("/api/auth/password/forgot", Map.of("tenantSlug", "tenant-one", "email", "admin@example.com"));
        var unknown = http("/api/auth/password/forgot", Map.of("tenantSlug", "tenant-one", "email", "unknown@example.com"));
        assertThat(known.statusCode()).isEqualTo(202); assertThat(unknown.statusCode()).isEqualTo(202);
        assertThat(body(known).get("message").asText()).isEqualTo(body(unknown).get("message").asText());
        assertThat(body(known).properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("challengeId", "message");
        UUID challenge=UUID.fromString(body(known).get("challengeId").asText()); String code=code(challenge);
        assertThat(redis.opsForHash().entries("notifyhub:auth:v1:otp:" + challenge).toString()).doesNotContain(code, "admin@example.com");
        assertThat(db.queryForObject("SELECT encrypted_code FROM auth.recovery_mail_outbox WHERE challenge_id=?", String.class, challenge)).doesNotContain(code);
        assertThat(publisher.publishOne()).isTrue();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://"+MAIL.getHost()+":"+MAIL.getMappedPort(8025)+"/api/v2/search?kind=containing&query="+challenge)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(body(response).get("total").asInt()).isEqualTo(1);
        assertThat(response.body()).contains(code, "admin@example.com");
        assertThat(db.queryForObject("SELECT encrypted_code FROM auth.recovery_mail_outbox WHERE challenge_id=?", String.class, challenge)).isNull();
        assertThat(publisher.publishOne()).isFalse();
        assertThat(body(unknown).has("code")).isFalse(); assertThat(body(unknown).has("password")).isFalse();
    }

    @Test void reset_changes_password_revokes_sessions_and_cannot_be_used_twice_even_if_redis_proof_remains() throws Exception {
        var challenge=issue(); String code=code(challenge);
        assertThat(http("/api/auth/password/reset", resetBody(challenge, code, NEW)).statusCode()).isEqualTo(204);
        assertThatThrownBy(() -> identities.login("tenant-one", "admin@example.com", OLD)).isInstanceOf(AuthFailure.class);
        assertThat(identities.login("tenant-one", "admin@example.com", NEW).accessToken()).isNotBlank();
        assertThatThrownBy(() -> identities.refresh(tokens.refreshToken())).isInstanceOf(AuthFailure.class);
        controls.issue(challenge, code, Duration.ofMinutes(5));
        assertThat(http("/api/auth/password/reset", resetBody(challenge, code, OLD)).statusCode()).isEqualTo(401);
        assertThat(identities.login("tenant-one", "admin@example.com", NEW).accessToken()).isNotBlank();
    }

    @Test void five_wrong_guesses_destroy_proof_and_never_change_password() throws Exception {
        var challenge=issue(); String code=code(challenge), wrong=code.equals("00000000")?"99999999":"00000000";
        for(int i=0;i<5;i++) assertThat(http("/api/auth/password/reset", resetBody(challenge,wrong,NEW)).statusCode()).isEqualTo(401);
        assertThat(controls.active(challenge)).isFalse();
        assertThat(http("/api/auth/password/reset", resetBody(challenge,code,NEW)).statusCode()).isEqualTo(401);
        assertThat(identities.login("tenant-one", "admin@example.com", OLD).accessToken()).isNotBlank();
        assertThat(publisher.publishOne()).isTrue();
        assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox",String.class)).isEqualTo("EXPIRED");
    }

    @Test void otp_expiry_is_enforced_by_redis_and_database() throws Exception {
        var challenge=issue(); String code=code(challenge);
        redis.expire("notifyhub:auth:v1:otp:"+challenge, Duration.ofMillis(1));
        await(() -> !controls.active(challenge));
        assertThat(http("/api/auth/password/reset",resetBody(challenge,code,NEW)).statusCode()).isEqualTo(401);
        controls.issue(challenge,code,Duration.ofMinutes(5));
        db.update("UPDATE auth.recovery_challenges SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),challenge);
        assertThat(http("/api/auth/password/reset",resetBody(challenge,code,NEW)).statusCode()).isEqualTo(401);
        publisher.publishOne(); assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox",String.class)).isEqualTo("EXPIRED");
    }

    @Test void resend_invalidates_previous_challenge_without_changing_password() {
        var first=issue(); String oldCode=code(first); flushRedis(); var second=issue(); String latest=code(second);
        controls.issue(first,oldCode,Duration.ofMinutes(5));
        assertThatThrownBy(() -> recovery.reset(first,oldCode,NEW)).isInstanceOf(AuthFailure.class);
        recovery.reset(second,latest,NEW);
        assertThat(identities.login("tenant-one","admin@example.com",NEW).accessToken()).isNotBlank();
    }

    @Test void code_is_bound_to_challenge_and_tenant_even_for_same_email() {
        var first=issue(); String code1=code(first);
        var secondTokens=identities.register("tenant-two","Tenant Two","admin@example.com","Other Admin",OLD);
        var second=recovery.request("tenant-two","admin@example.com","127.0.0.1").challengeId(); String code2=code(second);
        // Deterministic binding assertion also covers coincidentally equal codes without a flaky assertion.
        assertThat(crypto.digest("otp",first+":"+code1)).isNotEqualTo(crypto.digest("otp",second+":"+code1));
        if (!code1.equals(code2)) assertThatThrownBy(() -> recovery.reset(second,code1,NEW)).isInstanceOf(AuthFailure.class);
        recovery.reset(first,code1,NEW);
        assertThat(identities.login("tenant-two","admin@example.com",OLD).accessToken()).isNotBlank();
        assertThat(identities.refresh(secondTokens.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test void concurrent_resets_have_one_database_receipt_and_one_winner() throws Exception {
        var challenge=issue(); String code=code(challenge);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> reset=()->{ try{ recovery.reset(challenge,code,NEW); return 204; }catch(AuthFailure failure){return failure.status().value();} };
            var results=executor.invokeAll(List.of(reset,reset));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(204,401);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM auth.recovery_challenges WHERE consumed_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }

    @Test void password_transaction_rollback_keeps_otp_for_retry_and_preserves_refresh_session() {
        var challenge=issue(); String code=code(challenge);
        db.execute("CREATE OR REPLACE FUNCTION auth.reject_password() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected password write failure'; END; $$ LANGUAGE plpgsql");
        db.execute("CREATE TRIGGER reject_password BEFORE UPDATE OF password_hash ON auth.users FOR EACH ROW EXECUTE FUNCTION auth.reject_password()");
        assertThatThrownBy(()->recovery.reset(challenge,code,NEW)).isInstanceOf(ControlFailure.class);
        assertThat(controls.active(challenge)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM auth.recovery_challenges WHERE consumed_at IS NOT NULL",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE revoked_at IS NOT NULL",Integer.class)).isZero();
        db.execute("DROP TRIGGER reject_password ON auth.users"); recovery.reset(challenge,code,NEW);
        assertThat(identities.login("tenant-one","admin@example.com",NEW).accessToken()).isNotBlank();
    }

    @Test void failed_outbox_insert_rolls_back_recovery_version_and_removes_redis_proof() {
        db.execute("CREATE OR REPLACE FUNCTION auth.reject_outbox() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected outbox failure'; END; $$ LANGUAGE plpgsql");
        db.execute("CREATE TRIGGER reject_outbox BEFORE INSERT ON auth.recovery_mail_outbox FOR EACH ROW EXECUTE FUNCTION auth.reject_outbox()");
        assertThatThrownBy(this::issue).isInstanceOf(ControlFailure.class);
        assertThat(db.queryForObject("SELECT recovery_version FROM auth.users",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM auth.recovery_challenges",Integer.class)).isZero();
        assertThat(redis.keys("notifyhub:auth:v1:otp:*")).isEmpty();
    }

    @Test void disable_and_reenable_cannot_resurrect_an_old_otp() {
        var member=identities.createUser(new AuthModels.Actor(tenant,user),"member@example.com","Member",OLD,Role.VIEWER);
        var challenge=recovery.request("tenant-one","member@example.com","127.0.0.1").challengeId(); String code=code(challenge);
        identities.changeEnabled(new AuthModels.Actor(tenant,user),member.id(),false);
        identities.changeEnabled(new AuthModels.Actor(tenant,user),member.id(),true);
        assertThatThrownBy(()->recovery.reset(challenge,code,NEW)).isInstanceOf(AuthFailure.class);
        assertThat(identities.login("tenant-one","member@example.com",OLD).accessToken()).isNotBlank();
    }

    @Test void normalized_login_rate_limit_survives_instances_and_includes_retry_after() throws Exception {
        for(int i=0;i<10;i++) assertThat(http("/api/auth/login",Map.of("tenantSlug","tenant-one","email","admin@example.com","password","wrong")).statusCode()).isEqualTo(401);
        var denied=http("/api/auth/login",Map.of("tenantSlug","TENANT-ONE","email"," ADMIN@example.com ","password",OLD));
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        assertThat(denied.body()).doesNotContain("admin@example.com");
        var another=new RedisControls(redis,crypto);
        assertThatThrownBy(()->another.rate("login-account",PasswordRecovery.account("tenant-one","admin@example.com"),new ControlProperties.Rate(10,Duration.ofMinutes(15)))).isInstanceOf(ControlFailure.class);
    }

    @Test void rate_limit_is_atomic_and_ttl_does_not_extend_when_denied() throws Exception {
        var policy=new ControlProperties.Rate(5,Duration.ofSeconds(1));
        try(var executor=Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> work=new ArrayList<>();
            for(int i=0;i<30;i++) work.add(()->{try{controls.rate("race","one",policy);return true;}catch(ControlFailure failure){return false;}});
            var results=executor.invokeAll(work); long allowed=0; for(var result:results) if(result.get()) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
        String key=redis.keys("notifyhub:auth:v1:rate:race:*").iterator().next(); Long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertThatThrownBy(()->controls.rate("race","one",policy)).isInstanceOf(ControlFailure.class);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        await(()->!Boolean.TRUE.equals(redis.hasKey(key))); assertThatCode(()->controls.rate("race","one",policy)).doesNotThrowAnyException();
    }

    @Test void resend_cooldown_and_rolling_account_quota_prevent_email_spam() throws Exception {
        issue(); assertThat(http("/api/auth/password/forgot",Map.of("tenantSlug","TENANT-ONE","email"," ADMIN@example.com ")).statusCode()).isEqualTo(429);
        assertThat(db.queryForObject("SELECT count(*) FROM auth.recovery_mail_outbox",Integer.class)).isEqualTo(1);
        // Cooldown expires independently; the longer quota remains across resends.
        redis.delete(redis.keys("notifyhub:auth:v1:rate:otp-cooldown:*")); issue();
        redis.delete(redis.keys("notifyhub:auth:v1:rate:otp-cooldown:*"));
        assertThatThrownBy(this::issue).isInstanceOf(ControlFailure.class);
    }

    @Test void forged_forwarded_ip_does_not_bypass_request_budget_and_malformed_json_also_counts() throws Exception {
        String key="notifyhub:auth:v1:rate:request-ip:"+crypto.digest("rate","127.0.0.1");
        redis.opsForValue().set(key,"1000",Duration.ofSeconds(1));
        var response=http("/api/auth/register",Map.of());
        assertThat(response.statusCode()).isEqualTo(429);
        await(()->!Boolean.TRUE.equals(redis.hasKey(key)));
        assertThat(http("/api/auth/register",Map.of()).statusCode()).isEqualTo(400);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
    }

    @Test void smtp_failure_retries_are_bounded_and_ciphertext_is_cleared_at_terminal_state() {
        var challenge=issue(); mail.setPort(1);
        for(int i=0;i<4;i++) { db.update("UPDATE auth.recovery_mail_outbox SET available_at=?",Timestamp.from(Instant.now().minusSeconds(5))); assertThat(publisher.publishOne()).isTrue(); }
        var row=db.queryForMap("SELECT state,attempts,encrypted_code,failure_code FROM auth.recovery_mail_outbox WHERE challenge_id=?",challenge);
        assertThat(row.get("state")).isEqualTo("FAILED"); assertThat(row.get("attempts")).isEqualTo(4);
        assertThat(row.get("encrypted_code")).isNull(); assertThat(row.get("failure_code")).isEqualTo("SmtpUnavailable");
        assertThat(publisher.publishOne()).isFalse();
    }

    @Test void smtp_recovery_sends_pending_email_after_outage() {
        issue(); mail.setPort(1); publisher.publishOne();
        assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox",String.class)).isEqualTo("PENDING");
        mail.setPort(MAIL.getMappedPort(1025)); db.update("UPDATE auth.recovery_mail_outbox SET available_at=?",Timestamp.from(Instant.now().minusSeconds(5))); publisher.publishOne();
        assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox",String.class)).isEqualTo("SENT");
    }

    @Test void tampered_outbox_payload_never_sends_mail() {
        var challenge=issue(); byte[] bytes=Base64.getDecoder().decode(db.queryForObject("SELECT encrypted_code FROM auth.recovery_mail_outbox",String.class)); bytes[bytes.length-1]^=1;
        db.update("UPDATE auth.recovery_mail_outbox SET encrypted_code=? WHERE challenge_id=?",Base64.getEncoder().encodeToString(bytes),challenge);
        assertThat(publisher.publishOne()).isTrue();
        assertThat(db.queryForObject("SELECT failure_code FROM auth.recovery_mail_outbox",String.class)).isEqualTo("InvalidEncryptedPayload");
    }

    @Test void json_only_reset_rejects_weak_password_without_consuming_code() throws Exception {
        var challenge=issue(); String code=code(challenge);
        assertThat(http("/api/auth/password/reset",resetBody(challenge,code,"short")).statusCode()).isEqualTo(400);
        assertThat(controls.active(challenge)).isTrue();
        assertThat(http("/api/auth/password/reset",resetBody(challenge,code,NEW)).statusCode()).isEqualTo(204);
    }

    @Test void redis_outage_fails_closed_for_auth_writes_while_public_jwks_remains_available() throws Exception {
        var challenge=issue(); String code=code(challenge);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(http("/api/auth/login",Map.of("tenantSlug","tenant-one","email","admin@example.com","password",OLD)).statusCode()).isEqualTo(503);
            assertThat(http("/api/auth/password/reset",resetBody(challenge,code,NEW)).statusCode()).isEqualTo(503);
            assertThatThrownBy(publisher::publishOne).isInstanceOf(ControlFailure.class);
            assertThat(db.queryForObject("SELECT attempts FROM auth.recovery_mail_outbox",Integer.class)).isZero();
            var jwks=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/.well-known/jwks.json")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(jwks.statusCode()).isEqualTo(200);
            assertThat(db.queryForObject("SELECT count(*) FROM auth.recovery_challenges WHERE consumed_at IS NOT NULL",Integer.class)).isZero();
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
    }

    @Test void successful_reset_enqueues_and_sends_security_notice_without_password_or_otp() throws Exception {
        var challenge=issue(); String code=code(challenge); recovery.reset(challenge,code,NEW);
        assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox WHERE kind='NOTICE'",String.class)).isEqualTo("PENDING");
        assertThat(publisher.publishOne()).isTrue();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://"+MAIL.getHost()+":"+MAIL.getMappedPort(8025)+"/api/v2/search?kind=containing&query="+challenge)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(body(response).get("total").asInt()).isEqualTo(1);
        assertThat(response.body()).contains("NotifyHub password changed").doesNotContain(NEW,OLD,code);
        assertThat(db.queryForObject("SELECT state FROM auth.recovery_mail_outbox WHERE kind='NOTICE'",String.class)).isEqualTo("SENT");
    }

    private UUID issue() { return recovery.request("tenant-one","admin@example.com","127.0.0.1").challengeId(); }
    private String code(UUID id) { return crypto.decrypt(db.queryForObject("SELECT encrypted_code FROM auth.recovery_mail_outbox WHERE challenge_id=? AND kind='CODE'",String.class,id)); }
    private Map<String,Object> resetBody(UUID id,String code,String password) { return Map.of("challengeId",id.toString(),"code",code,"password",password); }
    private HttpResponse<String> http(String path,Object value) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .header("X-Forwarded-For",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private tools.jackson.databind.JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void flushRedis() { try(var connection=redis.getConnectionFactory().getConnection()){connection.serverCommands().flushDb();} }
    private static void await(Callable<Boolean> predicate) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!predicate.call()) { if(System.nanoTime()>deadline) fail("Timed out waiting for Redis TTL"); Thread.sleep(20); }
    }
    private static Path tempDirectory() {try{return Files.createTempDirectory("notifyhub-recovery-it");}catch(Exception error){throw new IllegalStateException(error);}}
    @AfterAll static void cleanup() throws Exception {Files.deleteIfExists(KEYS.resolve("private.pem"));Files.deleteIfExists(KEYS.resolve("control.secret"));Files.deleteIfExists(KEYS);}
}

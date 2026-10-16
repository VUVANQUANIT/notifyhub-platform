package com.vuvanquan.notifyhub.auth.control;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RedisControls {
    private final StringRedisTemplate redis;
    private final ControlCrypto crypto;
    private static final RedisScript<Long> RATE = script("rate.lua");
    private static final RedisScript<Long> ISSUE = script("issue-otp.lua");
    private static final RedisScript<Long> VERIFY = script("verify-otp.lua");
    private static RedisScript<Long> script(String name) {
        try {
            // Immutable script text avoids retaining a servlet context's classloader across context restarts.
            String text = new ClassPathResource("redis/" + name, RedisControls.class.getClassLoader())
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return RedisScript.of(text, Long.class);
        } catch (java.io.IOException missing) { throw new ExceptionInInitializerError(missing); }
    }
    public RedisControls(StringRedisTemplate redis, ControlCrypto crypto) { this.redis = redis; this.crypto = crypto; }

    public void rate(String scope, String identity, ControlProperties.Rate policy) {
        long wait = execute(RATE, "notifyhub:auth:v1:rate:" + scope + ":" + crypto.digest("rate", identity),
                Integer.toString(policy.limit()), Long.toString(policy.window().toMillis()));
        if (wait > 0) throw new ControlFailure(HttpStatus.TOO_MANY_REQUESTS, Math.max(1, (wait+999)/1000), "Too many authentication requests");
    }
    public void issue(UUID challenge, String code, Duration ttl) {
        long stored = execute(ISSUE, otpKey(challenge), codeDigest(challenge, code), Long.toString(ttl.toMillis()));
        if (stored != 1) throw ControlFailure.unavailable();
    }
    public boolean verify(UUID challenge, String code, int attempts) {
        return execute(VERIFY, otpKey(challenge), codeDigest(challenge, code), Integer.toString(attempts)) == 1;
    }
    public void delete(UUID challenge) {
        try { redis.delete(otpKey(challenge)); } catch (org.springframework.dao.DataAccessException unavailable) { throw ControlFailure.unavailable(); }
    }
    public boolean active(UUID challenge) {
        try { return Boolean.TRUE.equals(redis.hasKey(otpKey(challenge))); }
        catch (org.springframework.dao.DataAccessException unavailable) { throw ControlFailure.unavailable(); }
    }
    private String otpKey(UUID challenge) { return "notifyhub:auth:v1:otp:" + challenge; }
    private String codeDigest(UUID challenge, String code) { return crypto.digest("otp", challenge + ":" + code); }
    private long execute(RedisScript<Long> script, String key, String... args) {
        try {
            Long result = redis.execute(script, List.of(key), (Object[]) args);
            if (result == null) throw ControlFailure.unavailable();
            return result;
        } catch (org.springframework.dao.DataAccessException unavailable) { throw ControlFailure.unavailable(); }
    }
}

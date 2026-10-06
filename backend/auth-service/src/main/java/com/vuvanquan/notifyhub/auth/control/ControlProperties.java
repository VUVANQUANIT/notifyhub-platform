package com.vuvanquan.notifyhub.auth.control;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.control")
public record ControlProperties(String secretPath, Rate requests, Rate login, Rate refresh, Rate reset,
        Otp otp, List<String> trustedProxyAddresses) {
    public record Rate(int limit, Duration window) {}
    public record Otp(Duration ttl, int attempts, Rate requests, Rate ipRequests, Duration resendCooldown, int mailAttempts) {}
}

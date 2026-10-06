package com.vuvanquan.notifyhub.auth.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthProperties(String issuer, String audience, String signingKeyPath,
        boolean registrationEnabled, Duration accessTtl, Duration refreshTtl) {}

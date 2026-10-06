package com.vuvanquan.notifyhub.auth.control;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ControlProperties.class)
public class ControlConfiguration {
    @Bean ControlCrypto controlCrypto(ControlProperties config, Environment environment) throws Exception {
        if (environment.acceptsProfiles(Profiles.of("local")) && environment.acceptsProfiles(Profiles.of("prod")))
            throw new IllegalStateException("local and prod profiles cannot be combined");
        for (var rate : List.of(config.requests(), config.login(), config.refresh(), config.reset(), config.otp().requests(), config.otp().ipRequests())) {
            if (rate.limit() < 1 || rate.limit() > 100_000 || rate.window().compareTo(Duration.ofSeconds(1)) < 0
                    || rate.window().compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid auth rate policy");
        }
        if (config.otp().ttl().compareTo(Duration.ofMinutes(1)) < 0 || config.otp().ttl().compareTo(Duration.ofMinutes(10)) > 0
                || config.otp().attempts() < 1 || config.otp().attempts() > 10 || config.otp().mailAttempts() < 1 || config.otp().mailAttempts() > 10
                || config.otp().resendCooldown().compareTo(Duration.ofSeconds(1)) < 0
                || config.otp().resendCooldown().compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("Invalid OTP policy");
        return new ControlCrypto(Path.of(config.secretPath()), environment.acceptsProfiles(Profiles.of("local")));
    }
}

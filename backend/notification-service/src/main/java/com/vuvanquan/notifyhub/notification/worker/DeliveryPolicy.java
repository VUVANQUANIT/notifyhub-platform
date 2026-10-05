package com.vuvanquan.notifyhub.notification.worker;

import java.time.Duration;
import java.util.Objects;

public record DeliveryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {
    public DeliveryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1 || maxAttempts > 10 || initialDelay.toMillis() < 1
                || maxDelay.compareTo(initialDelay) < 0 || maxDelay.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Attempts must be 1..10 and retry delays must be positive, ordered and at most one hour");
        }
    }

    public boolean shouldRetry(int attempts, boolean retryable) {
        return retryable && attempts < maxAttempts;
    }

    public Duration retryDelay(int attempts) {
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("Attempts must be 1..10");
        return Duration.ofMillis(Math.min(maxDelay.toMillis(), initialDelay.toMillis() * (1L << (attempts - 1))));
    }
}

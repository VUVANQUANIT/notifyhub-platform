package com.vuvanquan.notifyhub.campaign.messaging;

import java.time.Instant;

final class PublishRetry {
    private PublishRetry() {}

    static Instant nextAttempt(int previousAttempts) {
        return Instant.now().plusSeconds(Math.min(60, 1L << Math.min(previousAttempts, 6)));
    }

    static String error(Exception exception) {
        // Persist only the exception type; broker exceptions may contain credentials or payloads.
        return exception.getClass().getSimpleName();
    }
}

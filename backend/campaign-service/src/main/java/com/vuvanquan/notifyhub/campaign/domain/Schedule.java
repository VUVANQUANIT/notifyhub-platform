package com.vuvanquan.notifyhub.campaign.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class Schedule {

    private static final Schedule IMMEDIATE = new Schedule(null);

    private final Instant sendAt;

    private Schedule(Instant sendAt) {
        this.sendAt = sendAt;
    }

    public static Schedule immediate() {
        return IMMEDIATE;
    }

    public static Schedule at(Instant sendAt) {
        return new Schedule(Objects.requireNonNull(sendAt, "Scheduled time must not be null"));
    }

    public Optional<Instant> sendAt() {
        return Optional.ofNullable(sendAt);
    }

    boolean isFutureAt(Instant now) {
        return sendAt != null && sendAt.isAfter(now);
    }

    boolean isDueAt(Instant now) {
        return sendAt != null && !sendAt.isAfter(now);
    }

    void validateNotBefore(Instant earliest) {
        if (sendAt != null && sendAt.isBefore(earliest)) {
            throw new IllegalArgumentException("Scheduled time must not be in the past");
        }
    }
}

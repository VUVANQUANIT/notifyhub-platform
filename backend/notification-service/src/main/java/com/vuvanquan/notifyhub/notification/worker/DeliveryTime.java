package com.vuvanquan.notifyhub.notification.worker;

import java.time.*;
import java.time.temporal.ChronoUnit;

final class DeliveryTime {
    private DeliveryTime() {}
    // PostgreSQL timestamptz stores microseconds; normalize before comparing persisted due times.
    static Instant now(Clock clock) { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}

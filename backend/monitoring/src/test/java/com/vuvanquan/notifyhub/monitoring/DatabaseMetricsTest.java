package com.vuvanquan.notifyhub.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static com.vuvanquan.notifyhub.monitoring.DatabaseMetrics.Metric.gauge;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseMetricsTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MutableClock clock = new MutableClock();
    final DatabaseMetrics metrics = new DatabaseMetrics(jdbc, registry, "SELECT pending", List.of(
            gauge("pending", "notifyhub.pipeline.pending", "Pending rows", "queue", "outbox")), clock, 90000);

    @Test void scrape_uses_snapshot_and_does_not_query_database() {
        assertThat(value()).isNaN();
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending", 7L));
        metrics.refresh();
        assertThat(value()).isEqualTo(7);
        assertThat(value()).isEqualTo(7);
        assertThat(registry.get("notifyhub.monitoring.database.up").gauge().value()).isEqualTo(1);
        verify(jdbc, times(1)).queryForMap(anyString());
    }
    @Test void failure_invalidates_snapshot_and_recovery_restores_it() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending", 4L))
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn(Map.of("pending", 0L));
        metrics.refresh(); metrics.refresh();
        assertThat(value()).isNaN();
        assertThat(registry.get("notifyhub.monitoring.database.up").gauge().value()).isZero();
        assertThat(registry.get("notifyhub.monitoring.poll.failures").counter().count()).isEqualTo(1);
        metrics.refresh();
        assertThat(value()).isZero();
        assertThat(registry.get("notifyhub.monitoring.database.up").gauge().value()).isEqualTo(1);
    }
    @Test void stale_or_incomplete_data_is_unavailable() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending", 4L)).thenReturn(Map.of());
        metrics.refresh(); clock.now += 90001;
        assertThat(value()).isNaN();
        assertThat(registry.get("notifyhub.monitoring.database.up").gauge().value()).isZero();
        metrics.refresh();
        assertThat(registry.get("notifyhub.monitoring.poll.failures").counter().count()).isEqualTo(1);
    }
    double value() { return registry.get("notifyhub.pipeline.pending").tag("queue", "outbox").gauge().value(); }
    static class MutableClock extends Clock {
        long now = 1000000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}

package com.vuvanquan.notifyhub.monitoring;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Scrapes only read cached snapshots; database queries run on the polling schedule. */
public final class DatabaseMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMetrics.class);
    private final JdbcTemplate jdbc;
    private final String sql;
    private final List<Metric> metrics;
    private final Clock clock;
    private final long maxAgeMillis;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final Counter failures;
    private final Timer duration;

    public record Metric(String column, String name, String description, Tags tags) {
        public static Metric gauge(String column, String name, String description, String... tags) {
            return new Metric(column, name, description, Tags.of(tags));
        }
    }
    private record Snapshot(Map<String, Double> values, long collectedAt) {}

    public DatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry, String sql, List<Metric> metrics,
                           Clock clock, long maxAgeMillis) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.metrics = List.copyOf(metrics);
        this.clock = clock;
        this.maxAgeMillis = maxAgeMillis;
        jdbc.setQueryTimeout(5);
        failures = registry.counter("notifyhub.monitoring.poll.failures");
        duration = registry.timer("notifyhub.monitoring.poll.duration");
        Gauge.builder("notifyhub.monitoring.database.up", this, m -> m.fresh() ? 1 : 0)
                .description("Whether the cached database snapshot is valid and fresh").register(registry);
        Gauge.builder("notifyhub.monitoring.snapshot.timestamp", this, m -> {
            Snapshot s = m.snapshot.get();
            return s == null ? Double.NaN : s.collectedAt() / 1000.0;
        }).description("Unix time of the last successful database snapshot").register(registry);
        for (Metric metric : metrics) {
            Gauge.builder(metric.name(), this, m -> m.value(metric.column()))
                    .description(metric.description()).tags(metric.tags()).register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${monitoring.database.poll-interval-ms:15000}")
    public void refresh() {
        Timer.Sample sample = Timer.start();
        try {
            Map<String, Object> row = jdbc.queryForMap(sql);
            Map<String, Double> values = new HashMap<>();
            for (Metric metric : metrics) {
                if (!(row.get(metric.column()) instanceof Number value)) {
                    throw new IllegalStateException("Missing numeric metric column: " + metric.column());
                }
                values.put(metric.column(), value.doubleValue());
            }
            snapshot.set(new Snapshot(Map.copyOf(values), clock.millis()));
        } catch (RuntimeException failure) {
            // A failed poll must not turn unavailable data into a healthy zero or retain stale backlog.
            snapshot.set(null);
            failures.increment();
            LOG.warn("Database monitoring snapshot failed ({})", failure.getClass().getSimpleName());
        } finally {
            sample.stop(duration);
        }
    }

    private boolean fresh() {
        Snapshot current = snapshot.get();
        return current != null && clock.millis() - current.collectedAt() <= maxAgeMillis;
    }
    private double value(String column) {
        Snapshot current = snapshot.get();
        return current != null && clock.millis() - current.collectedAt() <= maxAgeMillis
                ? current.values().get(column) : Double.NaN;
    }
}

package com.vuvanquan.notifyhub.notification.monitoring;

import com.vuvanquan.notifyhub.monitoring.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import static com.vuvanquan.notifyhub.monitoring.DatabaseMetrics.Metric.gauge;

@Configuration(proxyBeanMethods = false)
@Profile("monitoring")
@EnableScheduling
@Import(MonitoringSecurity.class)
public class MonitoringConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    DatabaseMetrics databaseMetrics(DataSource dataSource, MeterRegistry registry,
            @Value("${monitoring.database.max-age-ms:90000}") long maxAgeMillis) throws IOException {
        String sql = new ClassPathResource("monitoring/database.sql").getContentAsString(StandardCharsets.UTF_8);
        return new DatabaseMetrics(new JdbcTemplate(dataSource), registry, sql, List.of(
                gauge("email_pending", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "EMAIL", "status", "PENDING"),
                gauge("email_retry_pending", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "EMAIL", "status", "RETRY_PENDING"),
                gauge("email_sent", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "EMAIL", "status", "SENT"),
                gauge("email_failed", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "EMAIL", "status", "FAILED"),
                gauge("sms_pending", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "SMS", "status", "PENDING"),
                gauge("sms_retry_pending", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "SMS", "status", "RETRY_PENDING"),
                gauge("sms_sent", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "SMS", "status", "SENT"),
                gauge("sms_failed", "notifyhub.delivery.state", "Persisted deliveries by state", "channel", "SMS", "status", "FAILED"),
                gauge("result_outbox_pending", "notifyhub.pipeline.pending", "Rows waiting for pipeline processing", "queue", "result_outbox"),
                gauge("handoffs_pending", "notifyhub.pipeline.pending", "Rows waiting for pipeline processing", "queue", "handoffs"),
                gauge("result_outbox_age", "notifyhub.pipeline.oldest.seconds", "Age of oldest pending pipeline row (handoffs: overdue since available_at)", "queue", "result_outbox"),
                gauge("handoffs_age", "notifyhub.pipeline.oldest.seconds", "Age of oldest pending pipeline row (handoffs: overdue since available_at)", "queue", "handoffs")), Clock.systemUTC(), maxAgeMillis);
    }
}

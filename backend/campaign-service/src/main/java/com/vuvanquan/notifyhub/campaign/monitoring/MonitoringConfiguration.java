package com.vuvanquan.notifyhub.campaign.monitoring;

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
                gauge("running", "notifyhub.campaign.running", "Running campaigns"),
                gauge("running_age", "notifyhub.campaign.running.oldest.seconds", "Age of oldest running campaign"),
                gauge("outbox_pending", "notifyhub.pipeline.pending", "Rows waiting for pipeline processing", "queue", "outbox"),
                gauge("tasks_pending", "notifyhub.pipeline.pending", "Rows waiting for pipeline processing", "queue", "tasks"),
                gauge("dispatch_pending", "notifyhub.pipeline.pending", "Rows waiting for pipeline processing", "queue", "dispatch"),
                gauge("outbox_age", "notifyhub.pipeline.oldest.seconds", "Age of oldest pending pipeline row", "queue", "outbox"),
                gauge("tasks_age", "notifyhub.pipeline.oldest.seconds", "Age of oldest pending pipeline row", "queue", "tasks"),
                gauge("dispatch_age", "notifyhub.pipeline.oldest.seconds", "Age of oldest pending pipeline row", "queue", "dispatch")), Clock.systemUTC(), maxAgeMillis);
    }
}

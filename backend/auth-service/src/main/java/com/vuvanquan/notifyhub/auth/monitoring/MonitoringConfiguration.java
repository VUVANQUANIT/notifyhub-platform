package com.vuvanquan.notifyhub.auth.monitoring;

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
                gauge("recovery_mail_pending", "notifyhub.pipeline.pending", "Recovery mail waiting for publishing", "queue", "recovery_mail"),
                gauge("recovery_mail_age", "notifyhub.pipeline.oldest.seconds", "Overdue age of oldest recovery mail", "queue", "recovery_mail"),
                gauge("recovery_mail_failed", "notifyhub.auth.recovery.mail.failed", "Retained failed recovery mail")), Clock.systemUTC(), maxAgeMillis);
    }
}

package com.vuvanquan.notifyhub.reporting.monitoring;

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
                gauge("running", "notifyhub.reporting.running", "Running campaigns in reporting projection"),
                gauge("unknown_expected", "notifyhub.reporting.unknown.expected", "Reports still awaiting expected recipient count"),
                gauge("pending", "notifyhub.reporting.pending", "Expected recipients without projected terminal results")), Clock.systemUTC(), maxAgeMillis);
    }
}

package com.vuvanquan.notifyhub.notification.events;

import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class NotificationMigrationIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @Test void backfills_one_stable_result_for_each_existing_terminal_delivery() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("notification").defaultSchema("notification").target("1").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        for (String status : new String[]{"SENT", "FAILED", "RETRY_PENDING"}) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO notification.deliveries(notification_id,tenant_id,campaign_id,recipient_id,correlation_id,
                        channel,payload_hash,status,attempts,provider_reference,last_error,next_attempt_at,created_at,updated_at,completed_at)
                    VALUES (?,?,?,?,?,'SMS',?,?,1,?,?,CASE WHEN ?='RETRY_PENDING' THEN now() ELSE NULL END,now(),now(),
                        CASE WHEN ? IN ('SENT','FAILED') THEN now() ELSE NULL END)
                    """, id, id, id, id, id, "a".repeat(64), status, status.equals("SENT") ? "sms-id" : null,
                    status.equals("SENT") ? null : "SmtpUnavailable", status, status);
        }
        var flyway = Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("notification").defaultSchema("notification").load();
        flyway.migrate();
        var json = JsonMapper.builder().findAndAddModules().build();
        var events = jdbc.query("SELECT envelope::text FROM notification.result_outbox", (rs, row) -> json.readValue(rs.getString(1), DeliveryResultEvent.class));
        assertThat(events).hasSize(2);
        assertThat(events).extracting(DeliveryResultEvent::eventType).containsExactlyInAnyOrder("NotificationSent", "NotificationFailed");
        flyway.migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.result_outbox", Long.class)).isEqualTo(2);
    }
}

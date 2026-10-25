package com.vuvanquan.notifyhub.reporting.projection;

import com.vuvanquan.notifyhub.contracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;

@Component
public class ReportProjection {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ReportProjection(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public void delivery(DeliveryResultEvent event) {
        var p = event.payload();
        jdbc.update("""
                INSERT INTO reporting.campaign_reports(tenant_id,campaign_id,status)
                VALUES (?,?,'RUNNING') ON CONFLICT DO NOTHING
                """, event.tenantId(), event.campaignId());
        jdbc.queryForObject("SELECT status FROM reporting.campaign_reports WHERE tenant_id=? AND campaign_id=? FOR UPDATE",
                String.class, event.tenantId(), event.campaignId());
        int inserted = jdbc.update("""
                INSERT INTO reporting.delivery_reports(notification_id,event_id,tenant_id,campaign_id,recipient_id,channel,
                    status,attempts,provider_reference,failure_code,occurred_at,envelope)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (notification_id) DO NOTHING
                """, p.notificationId(), event.eventId(), event.tenantId(), event.campaignId(), p.recipientId(), p.channel(),
                p.status(), p.attempts(), p.providerReference(), p.failureCode(), Timestamp.from(event.occurredAt()), json.writeValueAsString(event));
        if (inserted == 0) {
            String stored = jdbc.queryForObject("SELECT envelope::text FROM reporting.delivery_reports WHERE notification_id=?",
                    String.class, p.notificationId());
            var original = json.readValue(stored, DeliveryResultEvent.class);
            if (!original.tenantId().equals(event.tenantId()) || !original.campaignId().equals(event.campaignId())
                    || !original.correlationId().equals(event.correlationId()) || !original.payload().equals(p)
                    || !original.occurredAt().equals(event.occurredAt())) throw new IllegalArgumentException("Conflicting delivery result");
            return;
        }
        var expected = jdbc.queryForObject("SELECT expected FROM reporting.campaign_reports WHERE tenant_id=? AND campaign_id=?",
                Long.class, event.tenantId(), event.campaignId());
        if (expected != null && deliveryCount(event.tenantId(), event.campaignId()) > expected) {
            throw new IllegalArgumentException("Delivery count exceeds campaign expectation");
        }
        jdbc.update("UPDATE reporting.campaign_reports SET updated_at=now() WHERE tenant_id=? AND campaign_id=?",
                event.tenantId(), event.campaignId());
    }

    @Transactional
    public void progress(CampaignProgressEvent event) {
        String envelope = json.writeValueAsString(event);
        int inserted = jdbc.update("INSERT INTO reporting.progress_inbox(event_id,envelope) VALUES (?,?::jsonb) ON CONFLICT DO NOTHING",
                event.eventId(), envelope);
        if (inserted == 0) {
            String stored = jdbc.queryForObject("SELECT envelope::text FROM reporting.progress_inbox WHERE event_id=?", String.class, event.eventId());
            if (!json.readTree(stored).equals(json.readTree(envelope))) throw new IllegalArgumentException("Conflicting campaign event");
            return;
        }
        jdbc.update("INSERT INTO reporting.campaign_reports(tenant_id,campaign_id,status) VALUES (?,?,'RUNNING') ON CONFLICT DO NOTHING",
                event.tenantId(), event.campaignId());
        var current = jdbc.queryForObject("SELECT status,expected FROM reporting.campaign_reports WHERE tenant_id=? AND campaign_id=? FOR UPDATE",
                (rs, row) -> new Progress(rs.getString("status"), (Long) rs.getObject("expected")), event.tenantId(), event.campaignId());
        if (event.expected() != null && (deliveryCount(event.tenantId(), event.campaignId()) > event.expected()
                || current.expected() != null && !current.expected().equals(event.expected()))) {
            throw new IllegalArgumentException("Conflicting expected count");
        }
        if (!current.status().equals("RUNNING") && !event.status().equals("RUNNING") && !current.status().equals(event.status())) {
            throw new IllegalArgumentException("Conflicting campaign outcome");
        }
        jdbc.update("""
                INSERT INTO reporting.campaign_reports(tenant_id,campaign_id,status,expected,progress_event_id,progress_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,campaign_id) DO UPDATE
                SET status=EXCLUDED.status,expected=COALESCE(EXCLUDED.expected,reporting.campaign_reports.expected),progress_event_id=EXCLUDED.progress_event_id,
                    progress_at=EXCLUDED.progress_at,updated_at=now()
                WHERE reporting.campaign_reports.status='RUNNING'
                """, event.tenantId(), event.campaignId(), event.status(), event.expected(), event.eventId(), Timestamp.from(event.occurredAt()));
    }

    private long deliveryCount(java.util.UUID tenant, java.util.UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports WHERE tenant_id=? AND campaign_id=?", Long.class, tenant, campaign);
    }
    private record Progress(String status, Long expected) {}
}

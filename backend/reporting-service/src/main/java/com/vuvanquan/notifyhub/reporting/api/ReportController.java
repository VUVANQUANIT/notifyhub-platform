package com.vuvanquan.notifyhub.reporting.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final JdbcTemplate jdbc;
    private final TenantResolver tenants;
    private static final String CAMPAIGN_SELECT = """
            SELECT c.*, COALESCE(d.sent,0) AS sent,COALESCE(d.failed,0) AS failed,
                COALESCE(d.attempts,0) AS attempts
            FROM reporting.campaign_reports c LEFT JOIN LATERAL (
                SELECT count(*) FILTER(WHERE status='SENT') AS sent,
                    count(*) FILTER(WHERE status='FAILED') AS failed, sum(attempts) AS attempts
                FROM reporting.delivery_reports WHERE tenant_id=c.tenant_id AND campaign_id=c.campaign_id
            ) d ON true
            """;
    public ReportController(JdbcTemplate jdbc, TenantResolver tenants) { this.jdbc = jdbc; this.tenants = tenants; }

    @GetMapping("/summary")
    public Summary summary(HttpServletRequest request, Authentication auth) {
        UUID tenant = tenants.resolve(request, auth);
        return jdbc.queryForObject("""
                SELECT count(*) AS deliveries,count(*) FILTER(WHERE status='SENT') AS sent,
                    count(*) FILTER(WHERE status='FAILED') AS failed,COALESCE(sum(attempts),0) AS attempts
                FROM reporting.delivery_reports WHERE tenant_id=?
                """, (rs, row) -> new Summary(rs.getLong("deliveries"), rs.getLong("sent"), rs.getLong("failed"), rs.getLong("attempts")), tenant);
    }

    @GetMapping("/campaigns")
    public Page<CampaignReport> campaigns(HttpServletRequest request, Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        UUID tenant = tenants.resolve(request, auth);
        var items = jdbc.query(CAMPAIGN_SELECT + " WHERE c.tenant_id=? ORDER BY c.updated_at DESC,c.campaign_id LIMIT ? OFFSET ?",
                (rs, row) -> campaign(rs), tenant, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM reporting.campaign_reports WHERE tenant_id=?", Long.class, tenant);
        return new Page<>(items, total, page, size);
    }

    @GetMapping("/campaigns/{id}")
    public CampaignReport campaign(HttpServletRequest request, Authentication auth, @PathVariable UUID id) {
        UUID tenant = tenants.resolve(request, auth);
        var items = jdbc.query(CAMPAIGN_SELECT + " WHERE c.tenant_id=? AND c.campaign_id=?", (rs, row) -> campaign(rs), tenant, id);
        if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign report not found");
        return items.getFirst();
    }

    @GetMapping("/campaigns/{id}/deliveries")
    public Page<DeliveryReport> deliveries(HttpServletRequest request, Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        UUID tenant = tenants.resolve(request, auth);
        if (status != null && !Set.of("SENT", "FAILED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be SENT or FAILED");
        }
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reporting.campaign_reports WHERE tenant_id=? AND campaign_id=?)", Boolean.class, tenant, id));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign report not found");
        String filter = " WHERE tenant_id=? AND campaign_id=? AND (?::text IS NULL OR status=?)";
        long total = jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports" + filter, Long.class, tenant, id, status, status);
        var items = jdbc.query("SELECT * FROM reporting.delivery_reports" + filter + " ORDER BY occurred_at DESC,notification_id LIMIT ? OFFSET ?",
                (rs, row) -> new DeliveryReport(rs.getObject("notification_id", UUID.class), rs.getObject("recipient_id", UUID.class),
                        rs.getString("channel"), rs.getString("status"), rs.getInt("attempts"), rs.getString("failure_code"),
                        rs.getTimestamp("occurred_at").toInstant()), tenant, id, status, status, size, (long) page * size);
        return new Page<>(items, total, page, size);
    }

    private static CampaignReport campaign(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long expected = (Long) rs.getObject("expected");
        long sent = rs.getLong("sent"), failed = rs.getLong("failed");
        return new CampaignReport(rs.getObject("campaign_id", UUID.class), rs.getString("status"), expected,
                sent, failed, expected == null ? null : Math.max(0L, expected - sent - failed), rs.getLong("attempts"));
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size (1..100)");
    }
    public record Page<T>(List<T> items, long totalElements, int page, int size) {}
    public record Summary(long deliveries, long sent, long failed, long attempts) {}
    public record CampaignReport(UUID campaignId, String status, Long expected, long sent, long failed, Long pending, long attempts) {}
    public record DeliveryReport(UUID notificationId, UUID recipientId, String channel, String status, int attempts, String failureCode, Instant occurredAt) {}
}

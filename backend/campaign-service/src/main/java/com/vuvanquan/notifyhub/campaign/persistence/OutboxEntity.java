package com.vuvanquan.notifyhub.campaign.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", schema = "campaign")
public class OutboxEntity {
    @Id public UUID eventId;
    public UUID tenantId;
    public UUID campaignId;
    public String eventType;
    public int eventVersion = 1;
    public Instant occurredAt;
    public UUID correlationId;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, Object> payload;
    public Instant publishedAt;
}

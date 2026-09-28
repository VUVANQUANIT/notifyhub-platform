package com.vuvanquan.notifyhub.campaign.persistence;

import com.vuvanquan.notifyhub.campaign.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaigns", schema = "campaign")
public class CampaignEntity {
    @Id public UUID id;
    @Column(nullable = false) public UUID tenantId;
    public String name;
    @Enumerated(EnumType.STRING) public Channel channel;
    public String subject;
    public String body;
    public Instant scheduledAt;
    @Enumerated(EnumType.STRING) public CampaignStatus status;
    public UUID createdBy;
    public Instant createdAt;
    public Instant startedAt;
    @Version public Long version;
    public String createKey;
    public String createFingerprint;
    public String startKey;

    public Campaign toDomain() {
        return Campaign.restore(new CampaignId(id), new TenantId(tenantId), new CampaignName(name),
                channel, new MessageContent(subject, body),
                scheduledAt == null ? Schedule.immediate() : Schedule.at(scheduledAt),
                new UserId(createdBy), createdAt, status, startedAt);
    }

    public void apply(Campaign domain) {
        name = domain.name().value();
        subject = domain.content().subject();
        body = domain.content().body();
        status = domain.status();
        startedAt = domain.startedAt().orElse(null);
    }
}

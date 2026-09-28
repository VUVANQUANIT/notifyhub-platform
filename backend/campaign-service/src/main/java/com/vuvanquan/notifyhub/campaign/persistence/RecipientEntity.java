package com.vuvanquan.notifyhub.campaign.persistence;

import com.vuvanquan.notifyhub.campaign.domain.Channel;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "campaign_recipients", schema = "campaign")
public class RecipientEntity {
    @Id public UUID id;
    public UUID tenantId;
    public UUID campaignId;
    @Enumerated(EnumType.STRING) public Channel channel;
    public String destination;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, String> personalization;
    public UUID sourceImportId;
}

package com.vuvanquan.notifyhub.campaign.persistence;

import com.vuvanquan.notifyhub.campaign.domain.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "recipient_imports", schema = "campaign")
public class RecipientImportEntity {
    @Id public UUID id;
    public UUID tenantId;
    public UUID campaignId;
    public UUID requestedBy;
    public Instant createdAt;
    public Instant completedAt;
    @Enumerated(EnumType.STRING) public ImportStatus status;
    public long totalRows;
    public long validRows;
    public long duplicateRows;
    public long invalidRows;
    @JdbcTypeCode(SqlTypes.JSON) public List<RecipientRowError> errors = List.of();
    public String rejectionReason;
}

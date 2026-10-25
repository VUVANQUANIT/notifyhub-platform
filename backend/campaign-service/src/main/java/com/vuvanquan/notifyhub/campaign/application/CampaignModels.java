package com.vuvanquan.notifyhub.campaign.application;

import com.vuvanquan.notifyhub.campaign.domain.*;
import com.vuvanquan.notifyhub.campaign.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CampaignModels {
    private CampaignModels() {}

    public record CreateCampaign(@NotBlank @Size(max=255) String name, @NotNull Channel channel,
            @Size(max=998) String subject, @NotBlank @Size(max=100000) String body, Instant scheduledAt) {}

    public record CampaignView(UUID id, UUID tenantId, String name, Channel channel, String subject,
            String body, Instant scheduledAt, CampaignStatus status, UUID createdBy, Instant createdAt,
            Instant startedAt, long version) {
        public static CampaignView of(CampaignEntity c) {
            return new CampaignView(c.id, c.tenantId, c.name, c.channel, c.subject, c.body,
                    c.scheduledAt, c.status, c.createdBy, c.createdAt, c.startedAt, c.version);
        }
    }

    public record ImportView(UUID id, UUID campaignId, ImportStatus status, ImportSummary summary,
            List<RecipientRowError> errors, String rejectionReason, Instant createdAt, Instant completedAt) {
        public static ImportView of(RecipientImportEntity i) {
            return new ImportView(i.id, i.campaignId, i.status,
                    new ImportSummary(i.totalRows, i.validRows, i.duplicateRows, i.invalidRows),
                    List.copyOf(i.errors), i.rejectionReason, i.createdAt, i.completedAt);
        }
    }

    public record RecipientView(UUID id, String destination, Map<String,String> personalization, UUID sourceImportId) {
        public static RecipientView of(RecipientEntity r) {
            return new RecipientView(r.id, r.destination, Map.copyOf(r.personalization), r.sourceImportId);
        }
    }

    public record PageView<T>(List<T> items, long totalElements, int page, int size) {}
}

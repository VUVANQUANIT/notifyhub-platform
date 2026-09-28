package com.vuvanquan.notifyhub.campaign.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RecipientImport {

    private final RecipientImportId id;
    private final CampaignId campaignId;
    private final TenantId tenantId;
    private final UserId requestedBy;
    private final Instant createdAt;
    private ImportStatus status;
    private ImportSummary summary;
    private String rejectionReason;
    private Instant completedAt;

    private RecipientImport(
            RecipientImportId id,
            CampaignId campaignId,
            TenantId tenantId,
            UserId requestedBy,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Recipient import id must not be null");
        this.campaignId = Objects.requireNonNull(campaignId, "Campaign id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant id must not be null");
        this.requestedBy = Objects.requireNonNull(requestedBy, "Requester must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.status = ImportStatus.PENDING;
    }

    public static RecipientImport create(
            RecipientImportId id,
            CampaignId campaignId,
            TenantId tenantId,
            UserId requestedBy,
            Instant createdAt
    ) {
        return new RecipientImport(id, campaignId, tenantId, requestedBy, createdAt);
    }

    public void start(Instant startedAt) {
        ensureStatus(ImportStatus.PENDING, "Only a pending recipient import can start");
        ensureTransitionTime(startedAt);
        status = ImportStatus.PROCESSING;
    }

    public void complete(ImportSummary summary, Instant completedAt) {
        ensureStatus(ImportStatus.PROCESSING, "Only a processing recipient import can complete");
        this.summary = Objects.requireNonNull(summary, "Import summary must not be null");
        ensureTransitionTime(completedAt);
        this.completedAt = completedAt;
        this.status = ImportStatus.COMPLETED;
    }

    public void reject(String reason, Instant completedAt) {
        ensureStatus(ImportStatus.PROCESSING, "Only a processing recipient import can be rejected");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason must not be blank");
        }
        ensureTransitionTime(completedAt);
        this.rejectionReason = reason.trim();
        this.completedAt = completedAt;
        this.status = ImportStatus.REJECTED;
    }

    private void ensureStatus(ImportStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private void ensureTransitionTime(Instant time) {
        Objects.requireNonNull(time, "Transition time must not be null");
        if (time.isBefore(createdAt)) {
            throw new IllegalArgumentException("Transition time must not be before import creation");
        }
    }

    public ImportStatus status() {
        return status;
    }

    public Optional<ImportSummary> summary() {
        return Optional.ofNullable(summary);
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public CampaignId campaignId() {
        return campaignId;
    }

    public TenantId tenantId() {
        return tenantId;
    }
}

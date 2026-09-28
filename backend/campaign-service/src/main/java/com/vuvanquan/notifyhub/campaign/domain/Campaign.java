package com.vuvanquan.notifyhub.campaign.domain;

import java.time.Instant;
import java.util.Objects;

public final class Campaign {

    private final CampaignId id;
    private final TenantId tenantId;
    private CampaignName name;
    private final Channel channel;
    private MessageContent content;
    private final Schedule schedule;
    private final UserId createdBy;
    private final Instant createdAt;
    private CampaignStatus status;
    private Instant startedAt;

    private Campaign(
            CampaignId id,
            TenantId tenantId,
            CampaignName name,
            Channel channel,
            MessageContent content,
            Schedule schedule,
            UserId createdBy,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Campaign id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant id must not be null");
        this.name = Objects.requireNonNull(name, "Campaign name must not be null");
        this.channel = Objects.requireNonNull(channel, "Channel must not be null");
        this.content = Objects.requireNonNull(content, "Message content must not be null");
        this.schedule = Objects.requireNonNull(schedule, "Schedule must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "Creator must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.content.validateFor(this.channel);
        this.schedule.validateNotBefore(this.createdAt);
        this.status = CampaignStatus.DRAFT;
    }

    public static Campaign create(
            CampaignId id,
            TenantId tenantId,
            CampaignName name,
            Channel channel,
            MessageContent content,
            Schedule schedule,
            UserId createdBy,
            Instant createdAt
    ) {
        return new Campaign(id, tenantId, name, channel, content, schedule, createdBy, createdAt);
    }

    public static Campaign create(
            CampaignId id,
            TenantId tenantId,
            CampaignName name,
            Channel channel,
            MessageContent content,
            UserId createdBy,
            Instant createdAt
    ) {
        return create(id, tenantId, name, channel, content, Schedule.immediate(), createdBy, createdAt);
    }

    public void rename(CampaignName newName) {
        ensureDraft("Only a draft campaign can be renamed");
        this.name = Objects.requireNonNull(newName, "Campaign name must not be null");
    }

    public void changeContent(MessageContent newContent) {
        ensureDraft("Only a draft campaign can change content");
        Objects.requireNonNull(newContent, "Message content must not be null").validateFor(channel);
        this.content = newContent;
    }

    public void assertCanImportRecipients() {
        ensureDraft("Recipients can only be imported into a draft campaign");
    }

    public void start(CampaignReadiness readiness, Instant now) {
        ensureDraft("Campaign can only be started from DRAFT");
        Objects.requireNonNull(readiness, "Campaign readiness must not be null");
        Objects.requireNonNull(now, "Start time must not be null");
        ensureNotBeforeCreation(now);

        if (readiness.importInProgress()) {
            throw new IllegalStateException("Campaign cannot start while a recipient import is in progress");
        }
        if (readiness.validRecipientCount() == 0) {
            throw new IllegalStateException("Campaign must have at least one valid recipient");
        }

        if (schedule.isFutureAt(now)) {
            status = CampaignStatus.SCHEDULED;
            return;
        }
        beginRunning(now);
    }

    public void activateScheduled(Instant now) {
        ensureStatus(CampaignStatus.SCHEDULED, "Only a scheduled campaign can be activated");
        Objects.requireNonNull(now, "Activation time must not be null");
        if (!schedule.isDueAt(now)) {
            throw new IllegalStateException("Scheduled campaign is not due yet");
        }
        beginRunning(now);
    }

    public void complete() {
        ensureStatus(CampaignStatus.RUNNING, "Only a running campaign can be completed");
        status = CampaignStatus.COMPLETED;
    }

    public void fail() {
        ensureStatus(CampaignStatus.RUNNING, "Only a running campaign can fail");
        status = CampaignStatus.FAILED;
    }

    private void beginRunning(Instant now) {
        status = CampaignStatus.RUNNING;
        startedAt = now;
    }

    private void ensureDraft(String message) {
        ensureStatus(CampaignStatus.DRAFT, message);
    }

    private void ensureStatus(CampaignStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private void ensureNotBeforeCreation(Instant time) {
        if (time.isBefore(createdAt)) {
            throw new IllegalArgumentException("Transition time must not be before campaign creation");
        }
    }

    public CampaignStatus status() {
        return status;
    }

    // Persistence boundary: reconstruction still validates content and lifecycle consistency.
    public static Campaign restore(CampaignId id, TenantId tenantId, CampaignName name, Channel channel,
            MessageContent content, Schedule schedule, UserId createdBy, Instant createdAt,
            CampaignStatus status, Instant startedAt) {
        Campaign campaign = create(id, tenantId, name, channel, content, schedule, createdBy, createdAt);
        Objects.requireNonNull(status, "Campaign status must not be null");
        boolean hasStarted = status == CampaignStatus.RUNNING || status == CampaignStatus.COMPLETED
                || status == CampaignStatus.FAILED;
        if (hasStarted != (startedAt != null)) {
            throw new IllegalArgumentException("Campaign status and startedAt are inconsistent");
        }
        if (status == CampaignStatus.SCHEDULED && schedule.sendAt().isEmpty()) {
            throw new IllegalArgumentException("Scheduled campaign requires a scheduled time");
        }
        if (startedAt != null) {
            campaign.ensureNotBeforeCreation(startedAt);
            if (schedule.isFutureAt(startedAt)) {
                throw new IllegalArgumentException("Campaign cannot start before its scheduled time");
            }
        }
        campaign.status = status;
        campaign.startedAt = startedAt;
        return campaign;
    }

    public CampaignName name() {
        return name;
    }

    public MessageContent content() {
        return content;
    }

    public Schedule schedule() {
        return schedule;
    }

    public java.util.Optional<Instant> startedAt() {
        return java.util.Optional.ofNullable(startedAt);
    }
}

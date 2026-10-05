package com.vuvanquan.notifyhub.campaign.application;

import com.vuvanquan.notifyhub.campaign.domain.*;
import com.vuvanquan.notifyhub.campaign.persistence.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.vuvanquan.notifyhub.campaign.application.CampaignModels.*;

@Service
@Transactional
public class CampaignApplicationService {
    private final CampaignRepository campaigns;
    private final RecipientRepository recipients;
    private final RecipientImportRepository imports;
    private final OutboxRepository outbox;
    private final RecipientCsvReader csv;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public CampaignApplicationService(CampaignRepository campaigns, RecipientRepository recipients,
            RecipientImportRepository imports, OutboxRepository outbox, RecipientCsvReader csv,
            JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.imports = imports;
        this.outbox = outbox;
        this.csv = csv;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public CampaignView create(Actor actor, String key, CreateCampaign request) {
        validateKey(key);
        String fingerprint = fingerprint(actor.userId(), request);
        // Database-scoped serialization also covers requests handled by different service instances.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, actor.tenantId() + ":" + key);
                statement.execute();
            }
            return null;
        });
        var previous = campaigns.findByTenantIdAndCreateKey(actor.tenantId(), key);
        if (previous.isPresent()) {
            if (!previous.get().createFingerprint.equals(fingerprint)) {
                throw new IllegalStateException("Idempotency key was already used with different input");
            }
            return CampaignView.of(previous.get());
        }
        CampaignEntity entity = new CampaignEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = actor.tenantId();
        entity.createdBy = actor.userId();
        entity.createdAt = clock.instant();
        entity.channel = request.channel();
        entity.scheduledAt = request.scheduledAt() == null ? null
                : request.scheduledAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        entity.createKey = key;
        entity.createFingerprint = fingerprint;
        Campaign domain = Campaign.create(new CampaignId(entity.id), new TenantId(entity.tenantId),
                new CampaignName(request.name()), request.channel(),
                new MessageContent(request.subject(), request.body()),
                entity.scheduledAt == null ? Schedule.immediate() : Schedule.at(entity.scheduledAt),
                new UserId(entity.createdBy), entity.createdAt);
        entity.apply(domain);
        entity = campaigns.saveAndFlush(entity);
        event(entity, "CampaignCreated", entity.createdAt);
        return CampaignView.of(entity);
    }

    @Transactional(readOnly = true)
    public CampaignView get(Actor actor, UUID id) {
        return CampaignView.of(find(actor.tenantId(), id));
    }

    @Transactional(readOnly = true)
    public PageView<CampaignView> list(Actor actor, CampaignStatus status, int page, int size) {
        Pageable pageable = page(page, size, "createdAt");
        var result = status == null ? campaigns.findByTenantId(actor.tenantId(), pageable)
                : campaigns.findByTenantIdAndStatus(actor.tenantId(), status, pageable);
        return new PageView<>(result.map(CampaignView::of).getContent(), result.getTotalElements(), page, size);
    }

    public ImportView importCsv(Actor actor, UUID id, byte[] bytes) {
        CampaignEntity campaign = lock(actor.tenantId(), id);
        campaign.toDomain().assertCanImportRecipients();
        if (imports.existsByTenantIdAndCampaignIdAndStatus(actor.tenantId(), id, ImportStatus.PROCESSING)) {
            throw new IllegalStateException("A recipient import is already processing");
        }

        RecipientImportEntity batch = new RecipientImportEntity();
        batch.id = UUID.randomUUID();
        batch.tenantId = actor.tenantId();
        batch.campaignId = id;
        batch.requestedBy = actor.userId();
        batch.createdAt = clock.instant();
        RecipientImport domain = RecipientImport.create(new RecipientImportId(batch.id), new CampaignId(id),
                new TenantId(actor.tenantId()), new UserId(actor.userId()), batch.createdAt);
        domain.start(batch.createdAt);
        batch.status = domain.status();
        batch = imports.saveAndFlush(batch);

        List<RecipientRow> rows;
        try {
            rows = csv.read(bytes, campaign.channel);
        } catch (IllegalArgumentException exception) {
            domain.reject(exception.getMessage(), clock.instant());
            batch.status = domain.status();
            batch.rejectionReason = domain.rejectionReason().orElseThrow();
            batch.completedAt = domain.completedAt().orElseThrow();
            event(campaign, "RecipientImportRejected", batch.completedAt,
                    Map.of("importId", batch.id.toString(), "rejectionReason", batch.rejectionReason));
            return ImportView.of(batch);
        }

        // Only query destinations from this bounded upload, never load all campaign recipients.
        var validator = new RecipientImportValidator();
        var initial = validator.validate(campaign.channel, rows, Set.of());
        Set<Destination> existing = new HashSet<>();
        List<String> candidates = initial.acceptedRecipients().stream().map(r -> r.destination().value()).toList();
        if (!candidates.isEmpty()) {
            recipients.existing(actor.tenantId(), id, candidates)
                    .forEach(value -> existing.add(new Destination(campaign.channel, value)));
        }
        var result = validator.validate(campaign.channel, rows, existing);
        for (ValidatedRecipient accepted : result.acceptedRecipients()) {
            RecipientEntity recipient = new RecipientEntity();
            recipient.id = UUID.randomUUID();
            recipient.tenantId = actor.tenantId();
            recipient.campaignId = id;
            recipient.channel = campaign.channel;
            recipient.destination = accepted.destination().value();
            recipient.personalization = accepted.personalizationData().values();
            recipient.sourceImportId = batch.id;
            recipients.save(recipient);
        }
        domain.complete(result.summary(), clock.instant());
        batch.status = domain.status();
        batch.completedAt = domain.completedAt().orElseThrow();
        batch.totalRows = result.summary().totalRows();
        batch.validRows = result.summary().validRows();
        batch.duplicateRows = result.summary().duplicateRows();
        batch.invalidRows = result.summary().invalidRows();
        batch.errors = result.errors();
        imports.flush();
        event(campaign, "RecipientImportCompleted", batch.completedAt,
                Map.of("importId", batch.id.toString(), "totalRows", batch.totalRows,
                        "validRows", batch.validRows, "duplicateRows", batch.duplicateRows,
                        "invalidRows", batch.invalidRows));
        return ImportView.of(batch);
    }

    @Transactional(readOnly = true)
    public ImportView getImport(Actor actor, UUID id, UUID importId) {
        return ImportView.of(imports.findByTenantIdAndCampaignIdAndId(actor.tenantId(), id, importId)
                .orElseThrow(CampaignNotFound::new));
    }

    @Transactional(readOnly = true)
    public PageView<RecipientView> recipients(Actor actor, UUID id, int page, int size) {
        find(actor.tenantId(), id);
        var result = recipients.findByTenantIdAndCampaignId(actor.tenantId(), id, page(page, size, "id"));
        return new PageView<>(result.map(RecipientView::of).getContent(), result.getTotalElements(), page, size);
    }

    public CampaignView start(Actor actor, UUID id, String key) {
        validateKey(key);
        CampaignEntity entity = lock(actor.tenantId(), id);
        if (key.equals(entity.startKey)) return CampaignView.of(entity);
        Campaign domain = entity.toDomain();
        domain.start(new CampaignReadiness(
                recipients.countByTenantIdAndCampaignId(actor.tenantId(), id),
                imports.existsByTenantIdAndCampaignIdAndStatus(actor.tenantId(), id, ImportStatus.PROCESSING)),
                clock.instant());
        entity.apply(domain);
        entity.startKey = key;
        campaigns.flush();
        event(entity, entity.status == CampaignStatus.RUNNING ? "CampaignStarted" : "CampaignScheduled",
                clock.instant());
        return CampaignView.of(entity);
    }

    public void activateDue(UUID tenantId, UUID id) {
        CampaignEntity entity = lock(tenantId, id);
        Instant now = clock.instant();
        if (entity.status != CampaignStatus.SCHEDULED || entity.scheduledAt.isAfter(now)) return;
        Campaign domain = entity.toDomain();
        domain.activateScheduled(now);
        entity.apply(domain);
        event(entity, "CampaignStarted", now);
    }

    private CampaignEntity find(UUID tenantId, UUID id) {
        return campaigns.findByTenantIdAndId(tenantId, id).orElseThrow(CampaignNotFound::new);
    }

    private CampaignEntity lock(UUID tenantId, UUID id) {
        return campaigns.lock(tenantId, id).orElseThrow(CampaignNotFound::new);
    }

    private void event(CampaignEntity campaign, String type, Instant at) {
        event(campaign, type, at, Map.of());
    }

    private void event(CampaignEntity campaign, String type, Instant at, Map<String, Object> details) {
        OutboxEntity event = new OutboxEntity();
        event.eventId = UUID.randomUUID();
        event.correlationId = event.eventId;
        event.tenantId = campaign.tenantId;
        event.campaignId = campaign.id;
        event.eventType = type;
        event.occurredAt = at;
        event.payload = new HashMap<>(details);
        event.payload.put("campaignId", campaign.id.toString());
        event.payload.put("status", campaign.status.name());
        event.payload.put("channel", campaign.channel.name());
        if (type.equals("CampaignStarted")) {
            event.payload.put("expected", recipients.countByTenantIdAndCampaignId(campaign.tenantId, campaign.id));
        }
        outbox.save(event);
    }

    private String fingerprint(UUID userId, CreateCampaign request) {
        try {
            byte[] bytes = json.writeValueAsString(List.of(userId, request)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 128 characters");
        }
    }

    private static Pageable page(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size between 1 and 100");
        }
        Sort order = Sort.by(Sort.Direction.DESC, sort);
        if (!sort.equals("id")) order = order.and(Sort.by("id"));
        return PageRequest.of(page, size, order);
    }
}

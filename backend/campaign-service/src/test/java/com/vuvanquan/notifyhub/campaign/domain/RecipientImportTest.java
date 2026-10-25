package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientImportTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-26T01:00:00Z");

    @Test
    void new_import_is_pending() {
        RecipientImport recipientImport = recipientImport();

        assertThat(recipientImport.status()).isEqualTo(ImportStatus.PENDING);
        assertThat(recipientImport.summary()).isEmpty();
        assertThat(recipientImport.completedAt()).isEmpty();
    }

    @Test
    void processing_import_can_complete_with_a_consistent_summary() {
        RecipientImport recipientImport = recipientImport();
        ImportSummary summary = new ImportSummary(10, 7, 2, 1);

        recipientImport.start(CREATED_AT.plusSeconds(1));
        recipientImport.complete(summary, CREATED_AT.plusSeconds(2));

        assertThat(recipientImport.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(recipientImport.summary()).contains(summary);
        assertThat(recipientImport.completedAt()).contains(CREATED_AT.plusSeconds(2));
    }

    @Test
    void processing_import_can_be_rejected_with_a_reason() {
        RecipientImport recipientImport = recipientImport();
        recipientImport.start(CREATED_AT);

        recipientImport.reject("  malformed CSV header  ", CREATED_AT.plusSeconds(1));

        assertThat(recipientImport.status()).isEqualTo(ImportStatus.REJECTED);
        assertThat(recipientImport.rejectionReason()).contains("malformed CSV header");
    }

    @Test
    void import_cannot_complete_before_processing() {
        RecipientImport recipientImport = recipientImport();

        assertThatThrownBy(() -> recipientImport.complete(new ImportSummary(0, 0, 0, 0), CREATED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a processing recipient import can complete");
    }

    @Test
    void terminal_import_cannot_start_again() {
        RecipientImport recipientImport = recipientImport();
        recipientImport.start(CREATED_AT);
        recipientImport.complete(new ImportSummary(0, 0, 0, 0), CREATED_AT);

        assertThatThrownBy(() -> recipientImport.start(CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a pending recipient import can start");
    }

    @Test
    void transition_time_cannot_be_before_creation() {
        RecipientImport recipientImport = recipientImport();

        assertThatThrownBy(() -> recipientImport.start(CREATED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transition time must not be before import creation");
        assertThat(recipientImport.status()).isEqualTo(ImportStatus.PENDING);
    }

    private RecipientImport recipientImport() {
        return RecipientImport.create(
                new RecipientImportId(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                new CampaignId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                new TenantId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                new UserId(UUID.fromString("30000000-0000-0000-0000-000000000001")),
                CREATED_AT
        );
    }
}

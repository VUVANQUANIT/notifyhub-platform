package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientImportValidatorTest {

    private final RecipientImportValidator validator = new RecipientImportValidator();

    @Test
    void email_rows_are_normalized_deduplicated_and_validated_independently() {
        List<RecipientRow> rows = List.of(
                row(1, Map.of("email", " Alice@EXAMPLE.com ", "firstName", "Alice")),
                row(2, Map.of("email", "alice@example.com", "firstName", "Duplicate")),
                row(3, Map.of("email", "invalid")),
                row(4, Map.of("email", "existing@example.com"))
        );

        RecipientValidationResult result = validator.validate(
                Channel.EMAIL,
                rows,
                Set.of(Destination.email("existing@example.com"))
        );

        assertThat(result.summary()).isEqualTo(new ImportSummary(4, 1, 2, 1));
        assertThat(result.acceptedRecipients()).singleElement().satisfies(recipient -> {
            assertThat(recipient.rowNumber()).isEqualTo(1);
            assertThat(recipient.destination()).isEqualTo(Destination.email("alice@example.com"));
            assertThat(recipient.personalizationData().values())
                    .containsExactly(Map.entry("firstName", "Alice"));
        });
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.rowNumber()).isEqualTo(3);
            assertThat(error.message()).isEqualTo("Invalid email destination");
        });
    }

    @Test
    void sms_import_uses_phone_number_and_e164_normalization() {
        RecipientValidationResult result = validator.validate(
                Channel.SMS,
                List.of(row(1, Map.of("phoneNumber", "+84 (912) 345-678", "name", "Quan"))),
                Set.of()
        );

        assertThat(result.summary()).isEqualTo(new ImportSummary(1, 1, 0, 0));
        assertThat(result.acceptedRecipients().getFirst().destination().value()).isEqualTo("+84912345678");
    }

    @Test
    void missing_channel_destination_is_a_row_error_not_a_batch_failure() {
        RecipientValidationResult result = validator.validate(
                Channel.EMAIL,
                List.of(row(1, Map.of("phoneNumber", "+84912345678"))),
                Set.of()
        );

        assertThat(result.summary()).isEqualTo(new ImportSummary(1, 0, 0, 1));
        assertThat(result.errors().getFirst().message()).isEqualTo("Destination value must not be null");
    }

    @Test
    void invalid_personalization_does_not_reserve_the_destination_as_a_duplicate() {
        RecipientRow invalid = row(1, mapAllowingNull("email", "same@example.com", "name", null));
        RecipientRow valid = row(2, Map.of("email", "same@example.com", "name", "Valid"));

        RecipientValidationResult result = validator.validate(Channel.EMAIL, List.of(invalid, valid), Set.of());

        assertThat(result.summary()).isEqualTo(new ImportSummary(2, 1, 0, 1));
        assertThat(result.acceptedRecipients().getFirst().rowNumber()).isEqualTo(2);
    }

    @Test
    void existing_destination_channel_must_match_campaign_channel() {
        assertThatThrownBy(() -> validator.validate(
                Channel.EMAIL,
                List.of(),
                Set.of(Destination.sms("+84912345678"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Existing destination channel must match campaign channel");
    }

    private RecipientRow row(long rowNumber, Map<String, String> values) {
        return new RecipientRow(rowNumber, values);
    }

    private Map<String, String> mapAllowingNull(String key1, String value1, String key2, String value2) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return values;
    }
}

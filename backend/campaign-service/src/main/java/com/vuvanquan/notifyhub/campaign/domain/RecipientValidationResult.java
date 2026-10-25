package com.vuvanquan.notifyhub.campaign.domain;

import java.util.List;
import java.util.Objects;

public record RecipientValidationResult(
        List<ValidatedRecipient> acceptedRecipients,
        List<RecipientRowError> errors,
        ImportSummary summary
) {

    public RecipientValidationResult {
        acceptedRecipients = List.copyOf(Objects.requireNonNull(acceptedRecipients, "Accepted recipients must not be null"));
        errors = List.copyOf(Objects.requireNonNull(errors, "Recipient errors must not be null"));
        Objects.requireNonNull(summary, "Import summary must not be null");
        if (acceptedRecipients.size() != summary.validRows()) {
            throw new IllegalArgumentException("Accepted recipient count must match import summary");
        }
        if (errors.size() != summary.invalidRows()) {
            throw new IllegalArgumentException("Recipient error count must match import summary");
        }
    }
}

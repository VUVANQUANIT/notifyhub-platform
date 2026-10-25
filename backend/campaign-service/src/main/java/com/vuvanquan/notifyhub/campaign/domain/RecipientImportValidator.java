package com.vuvanquan.notifyhub.campaign.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RecipientImportValidator {

    public RecipientValidationResult validate(
            Channel channel,
            List<RecipientRow> rows,
            Set<Destination> existingDestinations
    ) {
        Objects.requireNonNull(channel, "Campaign channel must not be null");
        Objects.requireNonNull(rows, "Recipient rows must not be null");
        Objects.requireNonNull(existingDestinations, "Existing destinations must not be null");

        Set<Destination> seen = new LinkedHashSet<>();
        for (Destination destination : existingDestinations) {
            Objects.requireNonNull(destination, "Existing destination must not be null");
            if (destination.channel() != channel) {
                throw new IllegalArgumentException("Existing destination channel must match campaign channel");
            }
            seen.add(destination);
        }

        List<ValidatedRecipient> accepted = new ArrayList<>();
        List<RecipientRowError> errors = new ArrayList<>();
        long duplicates = 0;
        String destinationField = channel == Channel.EMAIL ? "email" : "phoneNumber";

        for (RecipientRow row : rows) {
            Objects.requireNonNull(row, "Recipient row must not be null");
            try {
                Destination destination = new Destination(channel, row.values().get(destinationField));
                if (seen.contains(destination)) {
                    duplicates++;
                    continue;
                }

                Map<String, String> personalization = new LinkedHashMap<>(row.values());
                personalization.remove(destinationField);
                ValidatedRecipient recipient = new ValidatedRecipient(
                        row.rowNumber(),
                        destination,
                        new PersonalizationData(personalization)
                );
                seen.add(destination);
                accepted.add(recipient);
            } catch (IllegalArgumentException | NullPointerException exception) {
                errors.add(new RecipientRowError(row.rowNumber(), exception.getMessage()));
            }
        }

        ImportSummary summary = new ImportSummary(rows.size(), accepted.size(), duplicates, errors.size());
        return new RecipientValidationResult(accepted, errors, summary);
    }
}

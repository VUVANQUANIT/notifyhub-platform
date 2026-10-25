package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;

public record ValidatedRecipient(long rowNumber, Destination destination, PersonalizationData personalizationData) {

    public ValidatedRecipient {
        if (rowNumber < 1) {
            throw new IllegalArgumentException("Recipient row number must be positive");
        }
        Objects.requireNonNull(destination, "Destination must not be null");
        Objects.requireNonNull(personalizationData, "Personalization data must not be null");
    }
}

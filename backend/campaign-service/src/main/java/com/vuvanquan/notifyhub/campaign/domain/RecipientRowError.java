package com.vuvanquan.notifyhub.campaign.domain;

public record RecipientRowError(long rowNumber, String message) {

    public RecipientRowError {
        if (rowNumber < 1) {
            throw new IllegalArgumentException("Recipient row number must be positive");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Recipient row error must not be blank");
        }
    }
}

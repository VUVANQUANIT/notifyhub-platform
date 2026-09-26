package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RecipientRow(long rowNumber, Map<String, String> values) {

    public RecipientRow {
        if (rowNumber < 1) {
            throw new IllegalArgumentException("Recipient row number must be positive");
        }
        Objects.requireNonNull(values, "Recipient row values must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Recipient column name must not be blank");
            }
            copy.put(key, value);
        });
        values = Collections.unmodifiableMap(copy);
    }
}

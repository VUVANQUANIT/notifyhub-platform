package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PersonalizationData(Map<String, String> values) {

    public PersonalizationData {
        Objects.requireNonNull(values, "Personalization data must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Personalization key must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("Personalization value must not be null");
            }
            copy.put(key, value);
        });
        values = Collections.unmodifiableMap(copy);
    }

    public static PersonalizationData empty() {
        return new PersonalizationData(Map.of());
    }
}

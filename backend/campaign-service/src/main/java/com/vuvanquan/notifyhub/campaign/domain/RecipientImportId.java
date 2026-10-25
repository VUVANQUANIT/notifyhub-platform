package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;
import java.util.UUID;

public record RecipientImportId(UUID value) {

    public RecipientImportId {
        Objects.requireNonNull(value, "Recipient import id must not be null");
    }
}

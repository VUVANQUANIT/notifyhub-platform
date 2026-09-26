package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Objects;

public record MessageContent(String subject, String body) {

    public MessageContent {
        Objects.requireNonNull(body, "Message body must not be null");
        if (body.isBlank()) {
            throw new IllegalArgumentException("Message body must not be blank");
        }
        if (subject != null && subject.isBlank()) {
            throw new IllegalArgumentException("Email subject must not be blank");
        }
    }

    public static MessageContent email(String subject, String body) {
        return new MessageContent(Objects.requireNonNull(subject, "Email subject must not be null"), body);
    }

    public static MessageContent sms(String body) {
        return new MessageContent(null, body);
    }

    void validateFor(Channel channel) {
        Objects.requireNonNull(channel, "Channel must not be null");
        if (channel == Channel.EMAIL && subject == null) {
            throw new IllegalArgumentException("Email content must have a subject");
        }
        if (channel == Channel.SMS && subject != null) {
            throw new IllegalArgumentException("SMS content must not have a subject");
        }
    }
}

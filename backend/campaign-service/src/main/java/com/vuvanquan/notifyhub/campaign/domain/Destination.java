package com.vuvanquan.notifyhub.campaign.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Destination(Channel channel, String value) {

    private static final Pattern EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    public Destination {
        Objects.requireNonNull(channel, "Destination channel must not be null");
        Objects.requireNonNull(value, "Destination value must not be null");
        value = normalize(channel, value);
        validate(channel, value);
    }

    public static Destination email(String value) {
        return new Destination(Channel.EMAIL, value);
    }

    public static Destination sms(String value) {
        return new Destination(Channel.SMS, value);
    }

    private static String normalize(Channel channel, String value) {
        String trimmed = value.trim();
        if (channel == Channel.EMAIL) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.replaceAll("[\\s()-]", "");
    }

    private static void validate(Channel channel, String value) {
        if (channel == Channel.EMAIL && !EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email destination");
        }
        if (channel == Channel.SMS && !E164.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SMS destination; expected E.164 format");
        }
    }
}

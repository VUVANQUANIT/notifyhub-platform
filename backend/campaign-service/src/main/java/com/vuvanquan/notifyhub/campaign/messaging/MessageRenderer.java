package com.vuvanquan.notifyhub.campaign.messaging;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MessageRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]+)}}");
    private MessageRenderer() {}

    static String render(String template, Map<String, String> values) {
        if (template == null) return null;
        // One pass: recipient values cannot inject another template expression.
        return VARIABLE.matcher(template).replaceAll(match -> Matcher.quoteReplacement(
                values.getOrDefault(match.group(1).strip(), match.group())));
    }
}

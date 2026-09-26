package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageContentTest {

    @Test
    void email_has_subject_and_body() {
        MessageContent content = MessageContent.email("Welcome", "Hello");

        assertThat(content.subject()).isEqualTo("Welcome");
        assertThat(content.body()).isEqualTo("Hello");
    }

    @Test
    void sms_has_no_subject() {
        MessageContent content = MessageContent.sms("Hello");

        assertThat(content.subject()).isNull();
        assertThat(content.body()).isEqualTo("Hello");
    }

    @Test
    void email_subject_is_required() {
        assertThatThrownBy(() -> MessageContent.email(null, "Hello"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Email subject must not be null");
        assertThatThrownBy(() -> MessageContent.email("  ", "Hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email subject must not be blank");
    }

    @Test
    void message_body_is_required() {
        assertThatThrownBy(() -> MessageContent.sms(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Message body must not be null");
        assertThatThrownBy(() -> MessageContent.sms(" \n "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message body must not be blank");
    }
}

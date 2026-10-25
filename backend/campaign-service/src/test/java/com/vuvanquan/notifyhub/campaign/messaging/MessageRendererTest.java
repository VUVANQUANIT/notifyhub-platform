package com.vuvanquan.notifyhub.campaign.messaging;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MessageRendererTest {
    @Test void substitutes_once_without_interpreting_replacement_metacharacters() {
        assertThat(MessageRenderer.render("Hi {{ name }}", Map.of("name", "$5\\{{other}}", "other", "injected")))
                .isEqualTo("Hi $5\\{{other}}");
    }
    @Test void missing_variables_remain_literal() {
        assertThat(MessageRenderer.render("Hi {{missing}}", Map.of())).isEqualTo("Hi {{missing}}");
    }
    @Test void sms_subject_remains_null() {
        assertThat(MessageRenderer.render(null, Map.of())).isNull();
    }
}

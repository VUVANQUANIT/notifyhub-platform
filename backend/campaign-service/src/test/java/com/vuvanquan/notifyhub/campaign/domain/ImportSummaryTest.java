package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportSummaryTest {

    @Test
    void counters_must_add_up_to_total_rows() {
        assertThatThrownBy(() -> new ImportSummary(10, 5, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Import counters must add up to total rows");
    }

    @Test
    void counters_cannot_be_negative() {
        assertThatThrownBy(() -> new ImportSummary(1, -1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Import counters must not be negative");
    }
}

package com.vuvanquan.notifyhub.campaign.application;

import com.vuvanquan.notifyhub.campaign.domain.Channel;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class RecipientCsvReaderTest {
    private final RecipientCsvReader reader = new RecipientCsvReader();

    @Test void parses_bom_quoted_commas_and_multiline_personalization() {
        var rows = reader.read(("\uFEFFemail,name,note\r\na@example.com,\"Doe, Jane\",\"line1\nline2\"\r\n")
                .getBytes(StandardCharsets.UTF_8), Channel.EMAIL);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values()).containsEntry("name", "Doe, Jane").containsEntry("note", "line1\nline2");
    }

    @Test void rejects_duplicate_headers_missing_destination_and_invalid_utf8() {
        for (String csv : new String[]{"email,email\na,b\n", "name\nJane\n", "email\n"}) {
            assertThatThrownBy(() -> reader.read(csv.getBytes(StandardCharsets.UTF_8), Channel.EMAIL))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reader.read(new byte[]{(byte)0xC3, (byte)0x28}, Channel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejects_oversize_upload_and_excess_rows() {
        assertThatThrownBy(() -> reader.read(new byte[RecipientCsvReader.MAX_BYTES+1], Channel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        String csv = "email\n" + "a@example.com\n".repeat(10001);
        assertThatThrownBy(() -> reader.read(csv.getBytes(StandardCharsets.UTF_8), Channel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10000");
    }

    @Test void inconsistent_row_becomes_validation_error_while_next_row_survives() {
        var rows = reader.read("email,name\na@example.com,A,extra\nb@example.com,B\n".getBytes(StandardCharsets.UTF_8),
                Channel.EMAIL);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().values()).isEmpty();
        assertThat(rows.getLast().values()).containsEntry("email", "b@example.com");
    }
}

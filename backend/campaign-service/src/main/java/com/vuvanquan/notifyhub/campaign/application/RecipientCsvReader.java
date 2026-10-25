package com.vuvanquan.notifyhub.campaign.application;

import com.vuvanquan.notifyhub.campaign.domain.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

@Component
public class RecipientCsvReader {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 10000;

    public List<RecipientRow> read(byte[] bytes, Channel channel) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("CSV must contain between 1 byte and 2 MiB");
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            CSVFormat format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                    .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(true).get();
            try (CSVParser parser = format.parse(new StringReader(content))) {
                String required = channel == Channel.EMAIL ? "email" : "phoneNumber";
                if (!parser.getHeaderNames().contains(required)) {
                    throw new IllegalArgumentException("CSV header must contain " + required);
                }
                List<RecipientRow> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    if (rows.size() == MAX_ROWS) throw new IllegalArgumentException("CSV exceeds 10000 rows");
                    if (!record.isConsistent()) {
                        // A malformed row is represented as a missing destination and reported by the validator.
                        rows.add(new RecipientRow(record.getRecordNumber() + 1, Map.of()));
                    } else {
                        rows.add(new RecipientRow(record.getRecordNumber() + 1, record.toMap()));
                    }
                }
                if (rows.isEmpty()) throw new IllegalArgumentException("CSV must contain at least one data row");
                return rows;
            }
        } catch (IOException | UncheckedIOException exception) {
            throw new IllegalArgumentException("CSV must be well-formed UTF-8 CSV", exception);
        }
    }
}

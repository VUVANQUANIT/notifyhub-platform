package com.vuvanquan.notifyhub.campaign.domain;

public record ImportSummary(long totalRows, long validRows, long duplicateRows, long invalidRows) {

    public ImportSummary {
        if (totalRows < 0 || validRows < 0 || duplicateRows < 0 || invalidRows < 0) {
            throw new IllegalArgumentException("Import counters must not be negative");
        }
        if (totalRows != validRows + duplicateRows + invalidRows) {
            throw new IllegalArgumentException("Import counters must add up to total rows");
        }
    }
}

package org.gepnic.doors.masterapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReportPagination(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("page") int page,
        @JsonProperty("pageSize") int pageSize,
        @JsonProperty("totalRows") long totalRows,
        @JsonProperty("totalPages") long totalPages,
        @JsonProperty("threshold") int threshold,
        @JsonProperty("reason") String reason
) {
    public static ReportPagination disabled(long rowCount) {
        return new ReportPagination(false, 1, (int) Math.max(rowCount, 1L), rowCount, 1L, 100,
                "Result does not require server-side pagination");
    }
}

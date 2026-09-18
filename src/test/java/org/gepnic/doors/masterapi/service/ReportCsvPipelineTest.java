package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportCsvPipelineTest {
    @TempDir Path directory;

    @Test void realExportJobAndDownloadNeutralizeSyntheticValues() throws Exception {
        var source = mock(ReportViewerService.class);
        List<String> values = List.of("=1+1", "+1+1", "-1+1", "@SUM(1)",
                "  =1+1", "\t=1+1", "\r=1+1", "\n=1+1", "ordinary, \"text\"", "123");
        List<Map<String,Object>> rows = new ArrayList<>();
        for (String value : values) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("=heading", value);
            rows.add(row);
        }
        when(source.executeReport(any())).thenReturn(new ReportResult(rows, List.of(), Map.of(),
                Map.of(), ReportPagination.disabled(rows.size())));
        var service = new ReportExportService(source);
        ReflectionTestUtils.setField(service, "exportDirectory", directory.toString());
        try {
            UUID id = UUID.fromString((String) service.start(new ReportExecutionRequest(), "fixture-owner").get("jobId"));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Boolean.TRUE.equals(service.status(id, "fixture-owner").get("downloadReady"))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("COMPLETED", service.status(id, "fixture-owner").get("status"));
            var response = service.download(id, "fixture-owner");
            assertEquals(200, response.getStatusCode().value());
            String csv;
            try (var input = response.getBody().getInputStream()) {
                csv = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            StringBuilder expected = new StringBuilder("\"'=heading\"").append(System.lineSeparator());
            for (int i = 0; i < values.size(); i++) {
                String expectedValue = (i < 8 ? "'" : "") + values.get(i);
                expected.append('"').append(expectedValue.replace("\"", "\"\"")).append('"')
                        .append(System.lineSeparator());
            }
            assertEquals(expected.toString(), csv);
            assertThrows(SecurityException.class, () -> service.download(id, "other-owner"));
            verify(source).executeReport(any());
        } finally { service.shutdown(); }
    }
}

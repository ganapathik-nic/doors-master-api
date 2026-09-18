package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class ReportExportService {
    private final ReportViewerService reportViewerService;
    private final Map<UUID, ExportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${doors.exports.directory:./exports}")
    private String exportDirectory;

    public Map<String, Object> start(ReportExecutionRequest request, String username) {
        UUID id = UUID.randomUUID();
        ExportJob job = new ExportJob(id, username, "QUEUED", 0, 0L, null, null, Instant.now(), null);
        jobs.put(id, job);
        executor.submit(() -> generate(id, request));
        return status(id, username);
    }

    public Map<String, Object> status(UUID id, String username) {
        ExportJob job = ownedJob(id, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.id().toString());
        result.put("status", job.status());
        result.put("progress", job.progress());
        result.put("rowCount", job.rowCount());
        result.put("fileName", job.fileName());
        result.put("message", job.message());
        result.put("createdAt", job.createdAt());
        result.put("downloadReady", "COMPLETED".equals(job.status()));
        return result;
    }

    public ResponseEntity<Resource> download(UUID id, String username) {
        ExportJob job = ownedJob(id, username);
        if (!"COMPLETED".equals(job.status()) || job.path() == null || !Files.isRegularFile(job.path())) {
            throw new IllegalStateException("Export file is not ready");
        }
        Resource resource = new FileSystemResource(job.path());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.fileName() + "\"")
                .body(resource);
    }

    private void generate(UUID id, ReportExecutionRequest request) {
        ExportJob initial = jobs.get(id);
        jobs.put(id, initial.withState("RUNNING", 1, 0L, null, "Preparing export", null));
        Path output = null;
        try {
            Path directory = Path.of(exportDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            output = directory.resolve("report-export-" + id + ".csv").normalize();
            if (!output.startsWith(directory)) throw new SecurityException("Invalid export path");

            request.setPageSize(200);
            request.setPage(1);
            long written = 0L;
            long totalRows = 0L;
            long totalPages = 1L;
            List<String> headers = null;

            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (int page = 1; page <= totalPages; page++) {
                    request.setPage(page);
                    ReportResult result = reportViewerService.executeReport(request);
                    if (!result.nodeErrors().isEmpty()) {
                        throw new IllegalStateException(result.nodeErrors().toString());
                    }
                    if (page == 1) {
                        totalRows = result.pagination().totalRows();
                        totalPages = result.pagination().enabled() ? result.pagination().totalPages() : 1L;
                    }
                    List<Map<String, Object>> rows = result.data();
                    if (headers == null && !rows.isEmpty()) {
                        LinkedHashSet<String> names = new LinkedHashSet<>();
                        rows.forEach(row -> names.addAll(row.keySet()));
                        headers = new ArrayList<>(names);
                        writeCsvRow(writer, headers);
                    }
                    if (headers != null) {
                        for (Map<String, Object> row : rows) {
                            List<String> values = new ArrayList<>(headers.size());
                            for (String header : headers) values.add(stringify(row.get(header)));
                            writeCsvRow(writer, values);
                            written++;
                        }
                    }
                    int progress = totalPages > 0 ? (int) Math.min(99L, page * 100L / totalPages) : 99;
                    ExportJob current = jobs.get(id);
                    jobs.put(id, current.withState("RUNNING", progress, written, output,
                            "Exporting page " + page + " of " + totalPages, null));
                }
            }
            String fileName = "DOORS_Report_" + id.toString().substring(0, 8) + ".csv";
            ExportJob current = jobs.get(id);
            jobs.put(id, new ExportJob(id, current.owner(), "COMPLETED", 100, written, output,
                    fileName, current.createdAt(), "Export ready for download"));
        } catch (Exception exception) {
            if (output != null) try { Files.deleteIfExists(output); } catch (Exception ignored) {}
            ExportJob current = jobs.get(id);
            jobs.put(id, current.withState("FAILED", 100, current.rowCount(), null,
                    "Export failed", mostSpecificMessage(exception)));
        }
    }

    private void writeCsvRow(BufferedWriter writer, List<String> values) throws Exception {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            String value = spreadsheetSafeCell(values.get(index));
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.newLine();
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception ignored) { return String.valueOf(value); }
    }

    /** CSV quoting alone does not stop spreadsheet formula evaluation. */
    static String spreadsheetSafeCell(String value) {
        if (value == null || value.isEmpty()) return "";
        String trimmed = value.stripLeading();
        if (value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n'
                || (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)) {
            return "'" + value;
        }
        return value;
    }

    private ExportJob ownedJob(UUID id, String username) {
        ExportJob job = jobs.get(id);
        if (job == null) throw new NoSuchElementException("Export job not found");
        if (!Objects.equals(job.owner(), username)) throw new SecurityException("Export access denied");
        return job;
    }

    private String mostSpecificMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }

    private record ExportJob(UUID id, String owner, String status, int progress, long rowCount,
                             Path path, String fileName, Instant createdAt, String message) {
        ExportJob withState(String status, int progress, long rowCount, Path path, String message, String error) {
            return new ExportJob(id, owner, status, progress, rowCount, path, fileName, createdAt,
                    error != null ? message + ": " + error : message);
        }
    }
}

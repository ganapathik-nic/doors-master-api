package org.gepnic.doors.masterapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ATTRIBUTE = "X-DOORS-TRACE";
    private static final String PROBLEM_BASE = "https://doors.nic.in/problems/";

    @ExceptionHandler(DoorsApiException.class)
    public ResponseEntity<ProblemDetail> handleDoorsApi(
            DoorsApiException exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.error("[{}] {}: {}", traceId, exception.getCode(), exception.getMessage());
        return problem(
                request,
                exception.getStatus(),
                exception.getCode(),
                exception.getProblemType(),
                exception.getTitle(),
                exception.getMessage(),
                exception.isRetryable(),
                exception.getExtensions());
    }

    @ExceptionHandler({SecurityException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ProblemDetail> handleSecurity(
            RuntimeException exception,
            HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.FORBIDDEN,
                "DOORS-AUTH-ACCESS-DENIED",
                "access-denied",
                "Access denied",
                exception.getMessage(),
                false,
                Map.of());
    }

    @ExceptionHandler(EncryptionException.class)
    public ResponseEntity<ProblemDetail> handleEncryption(
            EncryptionException exception,
            HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DOORS-CRYPTO-FAILED",
                "cryptographic-processing-failed",
                "Cryptographic processing failed",
                "The secure request could not be processed. Contact NIC DOORS support with the trace ID.",
                false,
                Map.of());
    }

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            jakarta.validation.ConstraintViolationException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleValidation(Exception exception, HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "DOORS-REQUEST-INVALID", "invalid-request",
                "Invalid request", "Request fields are missing or invalid", false, Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "DOORS-REQUEST-INVALID",
                "invalid-request",
                "Invalid request",
                exception.getMessage(),
                false,
                Map.of());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            NoSuchElementException exception,
            HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "DOORS-RESOURCE-NOT-FOUND",
                "resource-not-found",
                "Resource not found",
                exception.getMessage(),
                false,
                Map.of());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> handleRuntime(
            RuntimeException exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.error("[{}] DOORS-INTERNAL-ERROR", traceId, exception);
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DOORS-INTERNAL-ERROR",
                "internal-error",
                "Internal server error",
                "The request could not be completed. Contact NIC DOORS support with the trace ID.",
                false,
                Map.of());
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String problemType,
            String title,
            String detail,
            boolean retryable,
            Map<String, Object> extensions) {
        String traceId = resolveTraceId(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + problemType));
        problem.setTitle(title);
        problem.setInstance(URI.create("/problems/occurrences/" + traceId));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId);
        problem.setProperty("retryable", retryable);
        extensions.forEach(problem::setProperty);

        return ResponseEntity.status(status)
                .header(TRACE_ATTRIBUTE, traceId)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ATTRIBUTE);
        if (existing != null && !existing.toString().isBlank()) {
            return existing.toString();
        }
        String traceId = "DOORS-TRC-" + UUID.randomUUID().toString()
                .substring(0, 8)
                .toUpperCase();
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        return traceId;
    }
}

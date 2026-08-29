package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DocumentManifestService {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)(?:\\.[^.]+)?$");

    private final ApiClientRepository clientRepository;
    private final DocumentServiceRegistrationRepository registrationRepository;
    private final ReportViewerService reportViewerService;
    private final ObjectMapper objectMapper;
    private final RegisteredDocumentServiceClient documentServiceClient;

    public Map<String, Object> discover(String serviceName, String queryName, String apiKey, Map<String, Object> request) {
        return discover(serviceName, queryName, apiKey, request, false);
    }

    public Map<String, Object> discover(String serviceName, String queryName, String apiKey,
                                        Map<String, Object> request, boolean previewDocumentCalls) {
        ApiClient caller = clientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        DocumentServiceRegistration registration = registrationRepository.findByServiceNameIgnoreCase(serviceName)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("Active document service not found: " + serviceName));
        if (registration.getManifestQueryName() == null || registration.getManifestClientName() == null) {
            throw new IllegalStateException("Document service has no manifest query and ClientName mapping");
        }
        if (!registration.getManifestClientName().equalsIgnoreCase(caller.getClientName())) {
            throw new SecurityException("API key does not belong to the ClientName mapped to this document service");
        }
        if (!registration.getManifestQueryName().equalsIgnoreCase(queryName)) {
            throw new SecurityException("Query Name does not match the Query mapped to this document service");
        }

        ReportExecutionRequest execution = ReportExecutionRequest.builder()
                .queryUniqueName(registration.getManifestQueryName())
                .agentId(registration.getAgentId())
                .performedBy(registration.getManifestClientName())
                .params(new LinkedHashMap<>(request))
                .page(1)
                .pageSize(200)
                .build();
        ReportResult source = reportViewerService.executeDocumentReport(execution);
        Object normalizedQueryData = normalizeJsonValues(source.data());
        List<Map<String, Object>> documents = new ArrayList<>();
        collectDocuments(normalizedQueryData, registration, request, documents);
        if (previewDocumentCalls) previewDocuments(registration, documents);
        else retrieveDocuments(registration, documents);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("docsServiceName", registration.getServiceName());
        response.put("agentId", registration.getAgentId());
        response.put("manifestQueryName", registration.getManifestQueryName());
        response.put("manifestClientName", registration.getManifestClientName());
        response.put("eligibilityMode", "OPEN");
        response.put("documentDownloadPolicyCode", registration.getDocumentDownloadPolicyCode());
        response.put("documentCallMode", previewDocumentCalls ? "PREVIEW" : "EXECUTED");
        Map<String, Object> intermediateQueryResponse = new LinkedHashMap<>();
        intermediateQueryResponse.put("data", normalizedQueryData);
        intermediateQueryResponse.put("offlineAgents", source.offlineAgents());
        intermediateQueryResponse.put("nodeErrors", source.nodeErrors());
        intermediateQueryResponse.put("pagination", source.pagination());
        response.put("intermediateQueryResponse", intermediateQueryResponse);
        response.put("documentCount", documents.size());
        response.put("retrievedDocumentCount", documents.stream()
                .filter(value -> "EXECUTED_AVAILABLE".equals(value.get("retrievalStatus"))).count());
        response.put("failedDocumentCount", documents.stream()
                .filter(value -> "EXECUTION_FAILED".equals(value.get("retrievalStatus"))).count());
        response.put("documents", documents);
        return response;
    }

    private void collectDocuments(Object node, DocumentServiceRegistration registration,
                                  Map<String, Object> request, List<Map<String, Object>> documents) {
        if (node instanceof Map<?, ?> map) {
            Object financial = value(map, "FINANCIAL_BID_DOC_DETAILS");
            if (financial instanceof Map<?, ?> details) {
                normalizeFinancial(details, registration, request, documents);
            } else if (value(map, "t_CHART_DETAILS") != null || value(map, "t_BID_DOCS") != null) {
                // ReportViewerService flattens one level of nested result maps.
                // Accept that internal representation as well as the original AOC JSON.
                normalizeFinancial(map, registration, request, documents);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (financial instanceof Map<?, ?>
                        && "FINANCIAL_BID_DOC_DETAILS".equalsIgnoreCase(String.valueOf(entry.getKey()))) continue;
                collectDocuments(entry.getValue(), registration, request, documents);
            }
        } else if (node instanceof Collection<?> collection) {
            for (Object child : collection) collectDocuments(child, registration, request, documents);
        } else if (node instanceof CharSequence text) {
            String json = text.toString().trim();
            if (json.startsWith("{") || json.startsWith("[")) {
                try {
                    collectDocuments(objectMapper.readValue(json, Object.class), registration, request, documents);
                } catch (Exception ignored) {
                    // Ordinary text values are not document manifests.
                }
            }
        }
    }

    private Object normalizeJsonValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeJsonValues(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : collection) normalized.add(normalizeJsonValues(item));
            return normalized;
        }
        if (value instanceof CharSequence text) {
            String candidate = text.toString().trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                try {
                    return normalizeJsonValues(objectMapper.readValue(candidate, Object.class));
                } catch (Exception ignored) {
                    // Preserve non-JSON text exactly as received.
                }
            }
        }
        return value;
    }

    private void normalizeFinancial(Map<?, ?> details, DocumentServiceRegistration registration,
                                    Map<String, Object> request, List<Map<String, Object>> documents) {
        Object bidDocs = value(details, "t_BID_DOCS");
        String derivedWorkItemRefNo = firstDocumentWorkItemRefNo(bidDocs);
        Object chartDetails = value(details, "t_CHART_DETAILS");
        if (chartDetails instanceof Map<?, ?> chartWrapper) {
            Object chart = value(chartWrapper, "t_CHART");
            if (chart instanceof Map<?, ?> chartMap) {
                String fileName = text(value(chartMap, "t_CHART_NAME"));
                String workItemRefNo = firstNonBlank(text(request.get("workItemRefNo")),
                        firstNonBlank(trailingNumber(fileName), derivedWorkItemRefNo));
                add(documents, registration, "BOQCHART", "BOQCHART", workItemRefNo, fileName,
                        text(request.get("bidId")), workItemRefNo, null, request);
            }
        }
        if (bidDocs instanceof Collection<?> bids) {
            for (Object bid : bids) {
                if (!(bid instanceof Map<?, ?> bidMap)) continue;
                String bidId = text(value(bidMap, "t_BID_ID"));
                Object docData = value(bidMap, "t_DOC_DATA");
                if (!(docData instanceof Collection<?> files)) continue;
                for (Object file : files) {
                    if (!(file instanceof Map<?, ?> fileMap)) continue;
                    String fileName = withDefaultExtension(
                            text(value(fileMap, "t_DOC_NAME")), ".xls");
                    add(documents, registration, text(value(fileMap, "t_DOC_TYPE")), "BIDPCK", bidId,
                            fileName, bidId,
                            firstNonBlank(text(request.get("workItemRefNo")), trailingNumber(fileName)),
                            text(value(fileMap, "t_DOC_CODE")), request);
                }
            }
        }
    }

    private String firstDocumentWorkItemRefNo(Object bidDocs) {
        if (!(bidDocs instanceof Collection<?> bids)) return null;
        for (Object bid : bids) {
            if (!(bid instanceof Map<?, ?> bidMap)) continue;
            Object docData = value(bidMap, "t_DOC_DATA");
            if (!(docData instanceof Collection<?> files)) continue;
            for (Object file : files) {
                if (!(file instanceof Map<?, ?> fileMap)) continue;
                String candidate = trailingNumber(text(value(fileMap, "t_DOC_NAME")));
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private void add(List<Map<String, Object>> documents, DocumentServiceRegistration registration,
                     String documentType, String serviceDocCode, String downloadId, String fileName,
                     String bidId, String workItemRefNo, String sourceDocumentCode,
                     Map<String, Object> request) {
        if (downloadId == null || fileName == null) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docsServiceName", registration.getServiceName());
        item.put("documentType", documentType);
        item.put("serviceDocCode", serviceDocCode);
        item.put("packetType", "Finance");
        item.put("downloadId", downloadId);
        item.put("fileName", fileName);
        item.put("bidId", bidId);
        item.put("workItemRefNo", workItemRefNo);
        item.put("sourceDocumentCode", sourceDocumentCode);
        documents.add(item);
    }

    private void retrieveDocuments(DocumentServiceRegistration registration,
                                   List<Map<String, Object>> documents) {
        for (Map<String, Object> document : documents) {
            String downloadId = required(document.get("downloadId"), "downloadId");
            String serviceDocCode = required(document.get("serviceDocCode"), "serviceDocCode");
            String fileName = required(document.get("fileName"), "fileName");
            String packetType = required(document.get("packetType"), "packetType");
            document.put("documentServiceCall", documentServiceCall(
                    registration, downloadId, serviceDocCode, fileName, packetType));
            try {
                RegisteredDocumentServiceClient.DocumentPayload payload = documentServiceClient.download(
                        registration, downloadId, serviceDocCode, fileName, packetType);
                document.put("retrievalStatus", "EXECUTED_AVAILABLE");
                document.put("contentType", payload.contentType().toString());
                document.put("contentLength", payload.content().length);
                document.put("downloadOperation", downloadOperation(registration, document));
            } catch (RuntimeException exception) {
                document.put("retrievalStatus", "EXECUTION_FAILED");
                document.put("retrievalError", retrievalError(exception));
            }
        }
    }

    private Map<String, Object> retrievalError(RuntimeException exception) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "DOCUMENT_SERVICE_CALL_FAILED");
        if (exception instanceof RestClientResponseException responseException) {
            error.put("upstreamStatus", responseException.getStatusCode().value());
            error.put("message", "Registered document service returned HTTP "
                    + responseException.getStatusCode().value());
        } else {
            Throwable cause = exception;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String message = cause.getMessage();
            error.put("message", message == null || message.isBlank()
                    ? cause.getClass().getSimpleName()
                    : truncate(message, 500));
        }
        return error;
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private void previewDocuments(DocumentServiceRegistration registration,
                                  List<Map<String, Object>> documents) {
        for (Map<String, Object> document : documents) {
            String downloadId = required(document.get("downloadId"), "downloadId");
            String serviceDocCode = required(document.get("serviceDocCode"), "serviceDocCode");
            String fileName = required(document.get("fileName"), "fileName");
            String packetType = required(document.get("packetType"), "packetType");
            document.put("retrievalStatus", "PREVIEW_NOT_EXECUTED");
            document.put("documentServiceCall", documentServiceCall(
                    registration, downloadId, serviceDocCode, fileName, packetType));
        }
    }

    private Map<String, Object> documentServiceCall(DocumentServiceRegistration registration,
                                                    String downloadId, String serviceDocCode,
                                                    String fileName, String packetType) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("method", "GET");
        call.put("endpoint", documentServiceClient.buildDownloadUri(
                registration, downloadId, serviceDocCode, fileName, packetType).toString());
        call.put("downloadId", downloadId);
        call.put("docCode", serviceDocCode);
        call.put("fileName", fileName);
        call.put("packetType", packetType);
        return call;
    }

    private Map<String, Object> downloadOperation(DocumentServiceRegistration registration,
                                                  Map<String, Object> document) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("method", "POST");
        operation.put("endpoint", "/api/v1/master/gateway/documents/services/"
                + registration.getServiceName() + "/download");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("downloadId", document.get("downloadId"));
        body.put("docCode", document.get("serviceDocCode"));
        body.put("fileName", document.get("fileName"));
        body.put("packetType", document.get("packetType"));
        operation.put("body", body);
        operation.put("returns", "BINARY_ATTACHMENT");
        return operation;
    }

    public RegisteredDocumentServiceClient.DocumentPayload downloadDocument(
            String serviceName, String apiKey, Map<String, Object> request) {
        clientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        DocumentServiceRegistration registration = registrationRepository.findByServiceNameIgnoreCase(serviceName)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException(
                        "Active document service not found: " + serviceName));
        return documentServiceClient.download(
                registration,
                required(request.get("downloadId"), "downloadId"),
                required(request.get("docCode"), "docCode"),
                safeDownloadFileName(required(request.get("fileName"), "fileName")),
                text(request.get("packetType")) == null ? "" : text(request.get("packetType")));
    }

    private String safeDownloadFileName(String value) {
        if (!value.equals(java.nio.file.Path.of(value).getFileName().toString()) || value.contains("..")) {
            throw new IllegalArgumentException("Invalid document filename");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Object value(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) return entry.getValue();
        }
        return null;
    }

    private String text(Object value) {
        String result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private String trailingNumber(String value) {
        if (value == null) return null;
        Matcher matcher = TRAILING_NUMBER.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    private String withDefaultExtension(String fileName, String extension) {
        if (fileName == null || fileName.isBlank()) return fileName;
        int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > lastSlash ? fileName : fileName + extension;
    }

    private String required(Object value, String field) {
        String result = text(value);
        if (result == null) throw new IllegalArgumentException(field + " is required");
        return result;
    }
}

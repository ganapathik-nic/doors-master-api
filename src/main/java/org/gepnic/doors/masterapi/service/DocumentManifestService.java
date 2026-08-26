package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> discover(String serviceName, String queryName, String apiKey, Map<String, Object> request) {
        ApiClient caller = clientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        DocumentServiceRegistration registration = registrationRepository.findByServiceNameIgnoreCase(serviceName)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("Active document service not found: " + serviceName));
        authorizeAgent(caller, registration.getAgentId());
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
        ReportResult source = reportViewerService.executeReport(execution);
        List<Map<String, Object>> documents = new ArrayList<>();
        collectDocuments(source.data(), registration, request, documents);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("docsServiceName", registration.getServiceName());
        response.put("agentId", registration.getAgentId());
        response.put("manifestQueryName", registration.getManifestQueryName());
        response.put("manifestClientName", registration.getManifestClientName());
        response.put("eligibilityMode", "OPEN");
        response.put("documentCount", documents.size());
        response.put("documents", documents);
        return response;
    }

    private void collectDocuments(Object node, DocumentServiceRegistration registration,
                                  Map<String, Object> request, List<Map<String, Object>> documents) {
        if (node instanceof Map<?, ?> map) {
            Object financial = value(map, "FINANCIAL_BID_DOC_DETAILS");
            if (financial instanceof Map<?, ?> details) normalizeFinancial(details, registration, request, documents);
            for (Object child : map.values()) collectDocuments(child, registration, request, documents);
        } else if (node instanceof Collection<?> collection) {
            for (Object child : collection) collectDocuments(child, registration, request, documents);
        }
    }

    private void normalizeFinancial(Map<?, ?> details, DocumentServiceRegistration registration,
                                    Map<String, Object> request, List<Map<String, Object>> documents) {
        Object chartDetails = value(details, "t_CHART_DETAILS");
        if (chartDetails instanceof Map<?, ?> chartWrapper) {
            Object chart = value(chartWrapper, "t_CHART");
            if (chart instanceof Map<?, ?> chartMap) {
                String fileName = text(value(chartMap, "t_CHART_NAME"));
                String downloadId = firstNonBlank(text(request.get("workItemRefNo")), trailingNumber(fileName));
                add(documents, registration, "BOQCHART", "BOQCHART", downloadId, fileName,
                        text(request.get("bidId")), text(request.get("workItemRefNo")), null);
            }
        }
        Object bidDocs = value(details, "t_BID_DOCS");
        if (bidDocs instanceof Collection<?> bids) {
            for (Object bid : bids) {
                if (!(bid instanceof Map<?, ?> bidMap)) continue;
                String bidId = text(value(bidMap, "t_BID_ID"));
                Object docData = value(bidMap, "t_DOC_DATA");
                if (!(docData instanceof Collection<?> files)) continue;
                for (Object file : files) {
                    if (!(file instanceof Map<?, ?> fileMap)) continue;
                    add(documents, registration, text(value(fileMap, "t_DOC_TYPE")), "BIDPCK", bidId,
                            text(value(fileMap, "t_DOC_NAME")), bidId,
                            firstNonBlank(text(request.get("workItemRefNo")), trailingNumber(text(value(fileMap, "t_DOC_NAME")))),
                            text(value(fileMap, "t_DOC_CODE")));
                }
            }
        }
    }

    private void add(List<Map<String, Object>> documents, DocumentServiceRegistration registration,
                     String documentType, String serviceDocCode, String downloadId, String fileName,
                     String bidId, String workItemRefNo, String sourceDocumentCode) {
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

    private void authorizeAgent(ApiClient client, String agentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_authorized_agents WHERE user_name = ? AND agent_id = ?",
                Integer.class, client.getClientName(), agentId);
        if (count == null || count == 0) throw new SecurityException("API Client is not authorized for Agent " + agentId);
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
}

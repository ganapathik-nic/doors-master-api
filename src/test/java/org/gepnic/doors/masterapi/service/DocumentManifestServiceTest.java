package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.dto.ReportPagination;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentManifestServiceTest {

    private ApiClientRepository clientRepository;
    private DocumentServiceRegistrationRepository registrationRepository;
    private ReportViewerService reportViewerService;
    private RegisteredDocumentServiceClient documentServiceClient;
    private DocumentManifestService service;
    private DocumentServiceRegistration registration;

    @BeforeEach
    void setUp() {
        clientRepository = mock(ApiClientRepository.class);
        registrationRepository = mock(DocumentServiceRegistrationRepository.class);
        reportViewerService = mock(ReportViewerService.class);
        documentServiceClient = mock(RegisteredDocumentServiceClient.class);
        service = new DocumentManifestService(clientRepository, registrationRepository,
                reportViewerService, new ObjectMapper(), documentServiceClient, mock(DocumentGrantService.class),
                mock(DocumentDownloadOrchestrationService.class));

        registration = new DocumentServiceRegistration();
        registration.setServiceName("AOC_DOCUMENTS");
        registration.setAgentId("agent-1");
        registration.setManifestQueryName("AOC_BY_TENDERID");
        registration.setManifestClientName("sdk-client");
        registration.setBaseUrl("https://example.test/nicgep_docs_web_service");
        registration.setDownloadPath("/Documents/downloadDocuments");
        registration.setIsActive(true);
    }

    @Test
    void discoveryPreparesDownloadWithoutFetchingOrHashingDocument() {
        when(clientRepository.findByApiKey("api-key")).thenReturn(Optional.of(client("sdk-client")));
        when(registrationRepository.findByServiceNameIgnoreCase("AOC_DOCUMENTS"))
                .thenReturn(Optional.of(registration));
        when(reportViewerService.executeDocumentReport(any())).thenReturn(manifestResult());
        when(documentServiceClient.buildDownloadUri(
                any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(URI.create("https://example.test/Documents/downloadDocuments"));

        Map<String, Object> result = service.discover(
                "AOC_DOCUMENTS", "AOC_BY_TENDERID", "api-key", Map.of("tenderId", "33993"));

        assertThat(result.get("documentCallMode")).isEqualTo("ON_DEMAND");
        assertThat(result.get("documentCount")).isEqualTo(1);
        assertThat(result.get("downloadableDocumentCount")).isEqualTo(1L);
        assertThat(result.get("retrievedDocumentCount")).isEqualTo(0L);

        @SuppressWarnings("unchecked")
        Map<String, Object> document = ((List<Map<String, Object>>) result.get("documents")).getFirst();
        assertThat(document.get("retrievalStatus")).isEqualTo("DOWNLOAD_READY");
        assertThat(document).containsKey("downloadOperation");
        assertThat(document).doesNotContainKeys(
                "sha256", "contentLength", "contentType", "documentServiceResponseCode");
        verify(documentServiceClient, never()).download(
                any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void discoveryHandlesFinancialBidAndTenderSummaryDocumentsFromAocPayload() {
        when(clientRepository.findByApiKey("api-key")).thenReturn(Optional.of(client("sdk-client")));
        when(registrationRepository.findByServiceNameIgnoreCase("AOC_DOCUMENTS"))
                .thenReturn(Optional.of(registration));
        when(reportViewerService.executeDocumentReport(any())).thenReturn(completeAocManifestResult());
        when(documentServiceClient.buildDownloadUri(
                any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(URI.create("https://example.test/Documents/downloadDocuments"));

        Map<String, Object> result = service.discover(
                "AOC_DOCUMENTS", "AOC_BY_TENDERID", "api-key", Map.of());

        assertThat(result.get("documentCount")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertThat(documents).extracting(value -> value.get("serviceDocCode"))
                .containsExactly("BOQCHART", "BIDPCK", "TECHEVALSUM", "FINEVALSUM", "AOC");
        assertThat(documents.getFirst()).containsEntry("outputFilePrefix", "2026_NIC_13149_1")
                .containsEntry("fileName", "boqcomparativechart.xlsx")
                .containsEntry("downloadId", "33975");
        Map<String, Object> technicalSummary = documents.stream()
                .filter(value -> "TECHEVALSUM".equals(value.get("serviceDocCode"))).findFirst().orElseThrow();
        assertThat(technicalSummary).containsEntry("downloadId", "33975")
                .containsEntry("fileName", "techsummary_33975.pdf")
                .containsEntry("packetType", "Technical")
                .containsEntry("sourceDocumentCode", "TECHEVALSUM33975");
        Map<String, Object> financialSummary = documents.stream()
                .filter(value -> "FINEVALSUM".equals(value.get("serviceDocCode"))).findFirst().orElseThrow();
        assertThat(financialSummary).containsEntry("packetType", "Finance");
        Map<String, Object> aoc = documents.stream()
                .filter(value -> "AOC".equals(value.get("serviceDocCode"))).findFirst().orElseThrow();
        assertThat(aoc).containsEntry("fileName", "letterofaward.pdf")
                .containsEntry("packetType", "AOC");
        assertThat(documents).noneMatch(value -> value.get("fileName") == null);
    }

    @Test
    void finalDownloadRejectsApiKeyBelongingToDifferentClient() {
        when(clientRepository.findByApiKey("other-key")).thenReturn(Optional.of(client("other-client")));
        when(registrationRepository.findByServiceNameIgnoreCase("AOC_DOCUMENTS"))
                .thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.downloadDocument("AOC_DOCUMENTS", "other-key", Map.of(
                "downloadId", "70128",
                "docCode", "BIDPCK",
                "fileName", "BOQ_33993.xls",
                "packetType", "Finance")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("API key does not belong to the ClientName mapped to this document service");

        verify(documentServiceClient, never()).download(
                any(), anyString(), anyString(), anyString(), anyString());
    }

    private ApiClient client(String clientName) {
        ApiClient client = new ApiClient();
        client.setClientName(clientName);
        client.setIsActive(true);
        return client;
    }

    private ReportResult manifestResult() {
        Map<String, Object> file = Map.of(
                "t_DOC_TYPE", "Financial Bid",
                "t_DOC_NAME", "BOQ_33993",
                "t_DOC_CODE", "SOURCE_CODE");
        Map<String, Object> bid = Map.of(
                "t_BID_ID", "70128",
                "t_DOC_DATA", List.of(file));
        Map<String, Object> financialDetails = Map.of("t_BID_DOCS", List.of(bid));
        Map<String, Object> data = Map.of("FINANCIAL_BID_DOC_DETAILS", financialDetails);
        return new ReportResult(
                List.of(data),
                List.of(),
                Map.of(),
                Map.of("agent-1", 200),
                ReportPagination.disabled(1));
    }


    private ReportResult completeAocManifestResult() {
        Map<String, Object> financial = Map.of(
                "t_CHART_DETAILS", Map.of("t_CHART", Map.of(
                        "t_CHART_TYPE", "BOQCHART", "t_CHART_NAME", "boqcomparativechart.xlsx")),
                "t_BID_DOCS", List.of(Map.of(
                        "t_BID_ID", 70080,
                        "t_DOC_DATA", List.of(Map.of(
                                "t_DOC_TYPE", "BOQ", "t_DOC_CODE", "BOQ70080",
                                "t_DOC_NAME", "BOQ_33975")))));
        Map<String, Object> summary = Map.of(
                "t_SUMMARY_DOCS", List.of(Map.of(
                        "t_SUMMARY_ID", 33975,
                        "t_DOC_DATA", List.of(
                                Map.of("t_DOC_TYPE", "BOS", "t_DOC_CODE", "TECHBOS33975", "t_DOC_NAME", ""),
                                Map.of("t_DOC_TYPE", "EVALSUM", "t_DOC_CODE", "TECHEVALSUM33975", "t_DOC_NAME", "techsummary_33975.pdf"),
                                Map.of("t_DOC_TYPE", "BOS", "t_DOC_CODE", "FINBOS33975", "t_DOC_NAME", ""),
                                Map.of("t_DOC_TYPE", "EVALSUM", "t_DOC_CODE", "FINEVALSUM33975", "t_DOC_NAME", "finsummary_33975.pdf"),
                                Map.of("t_DOC_TYPE", "AOC", "t_DOC_CODE", "AOC33975", "t_DOC_NAME", "letterofaward.pdf")))));
        return new ReportResult(
                List.of(Map.of(
                        "PUBLISHED_DETAILS", Map.of("t_ID", "2026_NIC_13149_1"),
                        "FINANCIAL_BID_DOC_DETAILS", financial,
                        "TENDER_SUMMARY_DOC_DETAILS", summary)),
                List.of(), Map.of(), Map.of("agent-1", 200), ReportPagination.disabled(1));
    }
}

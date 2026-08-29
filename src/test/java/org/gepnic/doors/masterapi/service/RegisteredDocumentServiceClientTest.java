package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisteredDocumentServiceClientTest {

    private StubDocumentDownloader documentDownloader;
    private RegisteredDocumentServiceClient client;
    private DocumentServiceRegistration registration;

    @BeforeEach
    void setUp() {
        documentDownloader = new StubDocumentDownloader();
        client = new RegisteredDocumentServiceClient(documentDownloader);

        registration = new DocumentServiceRegistration();
        registration.setBaseUrl("https://demoeproc.nic.in/nicgep_docs_webservice_v1");
        registration.setDownloadPath("/Documents/downloadDocuments");
        registration.setAccessMode("MASTER_DIRECT");
    }

    @Test
    void downloadsBinaryDocumentUsingRegisteredEndpointAndEncodedParameters() {
        byte[] expected = new byte[]{0x50, 0x4b, 0x03, 0x04};
        URI expectedUri = URI.create("https://demoeproc.nic.in/nicgep_docs_webservice_v1/Documents/downloadDocuments" +
                "?downloadId=33993&docCode=BOQCHART&fileName=boq%20comparative%20chart.xlsx" +
                "&packetType=Finance");
        documentDownloader.response = new DocumentDownloader.DownloadResponse(expected,
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        RegisteredDocumentServiceClient.DocumentPayload result = client.download(
                registration, "33993", "BOQCHART", "boq comparative chart.xlsx", "Finance");

        assertThat(result.content()).isEqualTo(expected);
        assertThat(result.contentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(documentDownloader.requestedUri).isEqualTo(expectedUri);
    }

    @Test
    void rejectsHtmlPortalPageInsteadOfReturningItAsAFile() {
        URI uri = client.buildDownloadUri(registration, "70128", "BIDPCK", "BOQ_33993.xls", "Finance");
        documentDownloader.response = new DocumentDownloader.DownloadResponse(
                "<html>Welcome</html>".getBytes(), MediaType.TEXT_HTML);

        assertThatThrownBy(() -> client.download(
                registration, "70128", "BIDPCK", "BOQ_33993.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document service returned HTML instead of the requested document");
    }

    @Test
    void rejectsEmptySuccessfulResponse() {
        URI uri = client.buildDownloadUri(registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance");
        documentDownloader.response = new DocumentDownloader.DownloadResponse(
                new byte[0], MediaType.APPLICATION_OCTET_STREAM);

        assertThatThrownBy(() -> client.download(
                registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document service did not return a document");
    }

    @Test
    void failsClosedForUnimplementedAgentProxyMode() {
        registration.setAccessMode("AGENT_PROXY");

        assertThatThrownBy(() -> client.download(
                registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_PROXY document access is not implemented");
    }

    private static final class StubDocumentDownloader implements DocumentDownloader {
        private URI requestedUri;
        private DownloadResponse response;

        @Override
        public DownloadResponse download(URI uri, DocumentServiceRegistration registration) {
            requestedUri = uri;
            return response;
        }
    }
}

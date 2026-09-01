package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

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
    void downloadsBinaryDocumentUsingRegisteredEndpointAndEncodedParameters() throws Exception {
        byte[] expected = new byte[]{0x50, 0x4b, 0x03, 0x04};
        URI expectedUri = URI.create("https://demoeproc.nic.in/nicgep_docs_webservice_v1/Documents/downloadDocuments" +
                "?downloadId=33993&docCode=BOQCHART&fileName=boq%20comparative%20chart.xlsx" +
                "&packetType=Finance");
        documentDownloader.response = response(expected,
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        RegisteredDocumentServiceClient.DocumentPayload result = client.download(
                registration, "33993", "BOQCHART", "boq comparative chart.xlsx", "Finance");

        assertThat(Files.readAllBytes(result.content())).isEqualTo(expected);
        assertThat(result.sha256()).isEqualTo(sha256(expected));
        result.close();
        assertThat(result.contentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(documentDownloader.requestedUri).isEqualTo(expectedUri);
    }

    @Test
    void rejectsHtmlPortalPageInsteadOfReturningItAsAFile() {
        URI uri = client.buildDownloadUri(registration, "70128", "BIDPCK", "BOQ_33993.xls", "Finance");
        documentDownloader.response = response("<html>Welcome</html>".getBytes(), MediaType.TEXT_HTML);

        assertThatThrownBy(() -> client.download(
                registration, "70128", "BIDPCK", "BOQ_33993.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document service returned HTML instead of the requested document");
    }

    @Test
    void rejectsEmptySuccessfulResponse() {
        URI uri = client.buildDownloadUri(registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance");
        documentDownloader.response = response(new byte[0], MediaType.APPLICATION_OCTET_STREAM);

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

    private static DocumentDownloader.DownloadResponse response(byte[] body, MediaType contentType) {
        try {
            var path = Files.createTempFile("document-test-", ".bin");
            Files.write(path, body);
            return new DocumentDownloader.DownloadResponse(path, body.length, sha256(body), contentType, 200);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

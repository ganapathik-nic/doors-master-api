package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RegisteredDocumentServiceClientTest {

    private MockRestServiceServer server;
    private RegisteredDocumentServiceClient client;
    private DocumentServiceRegistration registration;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new RegisteredDocumentServiceClient(restTemplate);

        registration = new DocumentServiceRegistration();
        registration.setBaseUrl("https://demoeproc.nic.in/nicgep_docs_webservice_v1");
        registration.setDownloadPath("/Documents/downloadDocuments");
        registration.setAccessMode("MASTER_DIRECT");
    }

    @Test
    void downloadsBinaryDocumentUsingRegisteredEndpointAndEncodedParameters() {
        byte[] expected = new byte[]{0x50, 0x4b, 0x03, 0x04};
        server.expect(once(), requestTo(
                        "https://demoeproc.nic.in/nicgep_docs_webservice_v1/Documents/downloadDocuments" +
                                "?downloadId=33993&docCode=BOQCHART&fileName=boq%20comparative%20chart.xlsx" +
                                "&packetType=Finance"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expected,
                        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));

        RegisteredDocumentServiceClient.DocumentPayload result = client.download(
                registration, "33993", "BOQCHART", "boq comparative chart.xlsx", "Finance");

        assertThat(result.content()).isEqualTo(expected);
        assertThat(result.contentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        server.verify();
    }

    @Test
    void rejectsHtmlPortalPageInsteadOfReturningItAsAFile() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("downloadId=70128")))
                .andRespond(withSuccess("<html>Welcome</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.download(
                registration, "70128", "BIDPCK", "BOQ_33993.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document service returned HTML instead of the requested document");
        server.verify();
    }

    @Test
    void rejectsEmptySuccessfulResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("docCode=NEGOTIEBIDDOC")))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> client.download(
                registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document service did not return a document");
        server.verify();
    }

    @Test
    void failsClosedForUnimplementedAgentProxyMode() {
        registration.setAccessMode("AGENT_PROXY");

        assertThatThrownBy(() -> client.download(
                registration, "403", "NEGOTIEBIDDOC", "BOQ_29543.xls", "Finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_PROXY document access is not implemented");
    }
}

package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
@RequiredArgsConstructor
public class RegisteredDocumentServiceClient {

    private final RestTemplate restTemplate;

    public DocumentPayload download(
            DocumentServiceRegistration registration,
            String downloadId,
            String docCode,
            String fileName,
            String packetType) {
        if (!"MASTER_DIRECT".equals(registration.getAccessMode())) {
            throw new IllegalStateException("AGENT_PROXY document access is not implemented");
        }
        URI url = UriComponentsBuilder.fromHttpUrl(registration.getBaseUrl() + registration.getDownloadPath())
                .queryParam("downloadId", downloadId)
                .queryParam("docCode", docCode)
                .queryParam("fileName", fileName)
                .queryParam("packetType", packetType)
                .build().encode().toUri();
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null || body.length == 0) {
            throw new IllegalStateException("Document service did not return a document");
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            throw new IllegalStateException("Document service returned HTML instead of the requested document");
        }
        return new DocumentPayload(body,
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType);
    }

    public record DocumentPayload(byte[] content, MediaType contentType) { }
}

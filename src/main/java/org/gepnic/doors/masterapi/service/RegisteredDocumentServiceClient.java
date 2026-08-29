package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
@RequiredArgsConstructor
public class RegisteredDocumentServiceClient {

    private final DocumentDownloader documentDownloader;

    public DocumentPayload download(
            DocumentServiceRegistration registration,
            String downloadId,
            String docCode,
            String fileName,
            String packetType) {
        if (!"MASTER_DIRECT".equals(registration.getAccessMode())) {
            throw new IllegalStateException("AGENT_PROXY document access is not implemented");
        }
        URI url = buildDownloadUri(registration, downloadId, docCode, fileName, packetType);
        DocumentDownloader.DownloadResponse response = documentDownloader.download(url, registration);
        byte[] body = response.body();
        if (body.length == 0) {
            throw new IllegalStateException("Document service did not return a document");
        }
        MediaType contentType = response.contentType();
        if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            throw new IllegalStateException("Document service returned HTML instead of the requested document");
        }
        return new DocumentPayload(body,
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType);
    }

    public URI buildDownloadUri(
            DocumentServiceRegistration registration,
            String downloadId,
            String docCode,
            String fileName,
            String packetType) {
        return UriComponentsBuilder.fromHttpUrl(registration.getBaseUrl() + registration.getDownloadPath())
                .queryParam("downloadId", downloadId)
                .queryParam("docCode", docCode)
                .queryParam("fileName", fileName)
                .queryParam("packetType", packetType)
                .build().encode().toUri();
    }

    public record DocumentPayload(byte[] content, MediaType contentType) { }
}

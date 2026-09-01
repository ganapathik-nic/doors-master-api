package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        if (response.contentLength() == 0) {
            closeQuietly(response);
            throw new IllegalStateException("Document service did not return a document");
        }
        MediaType contentType = response.contentType();
        if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            closeQuietly(response);
            throw new IllegalStateException("Document service returned HTML instead of the requested document");
        }
        return new DocumentPayload(response.body(), response.contentLength(), response.sha256(),
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType,
                response.responseCode(), url.toString());
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

    private static void closeQuietly(DocumentDownloader.DownloadResponse response) {
        try { response.close(); } catch (RuntimeException ignored) { }
    }

    public record DocumentPayload(Path content, long contentLength, String sha256, MediaType contentType,
                                  int responseCode, String upstreamEndpoint)
            implements AutoCloseable {
        public java.io.InputStream openStream() throws IOException { return Files.newInputStream(content); }
        @Override public void close() {
            try { Files.deleteIfExists(content); }
            catch (IOException e) { throw new IllegalStateException("Unable to remove temporary document", e); }
        }
    }
}

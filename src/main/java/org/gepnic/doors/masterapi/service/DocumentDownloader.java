package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.http.MediaType;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface DocumentDownloader {
    DownloadResponse download(URI uri, DocumentServiceRegistration registration);

    record DownloadResponse(Path body, long contentLength, String sha256, MediaType contentType, int responseCode)
            implements AutoCloseable {
        @Override
        public void close() {
            try { Files.deleteIfExists(body); }
            catch (IOException e) { throw new IllegalStateException("Unable to remove temporary document", e); }
        }
    }
}

package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.http.MediaType;

import java.net.URI;

public interface DocumentDownloader {
    DownloadResponse download(URI uri, DocumentServiceRegistration registration);

    record DownloadResponse(byte[] body, MediaType contentType) { }
}

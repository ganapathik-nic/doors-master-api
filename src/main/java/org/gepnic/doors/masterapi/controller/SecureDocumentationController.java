package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.service.SecureDocumentationService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/documentation")
@RequiredArgsConstructor
public class SecureDocumentationController {

    private final SecureDocumentationService service;

    @GetMapping("/catalogue")
    public ResponseEntity<List<SecureDocumentationService.DocumentSummary>> catalogue(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.visibleCatalogue(authentication));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<Resource> document(@PathVariable String documentId, Authentication authentication) throws IOException {
        SecureDocumentationService.SecuredDocument document = service.resolve(documentId, authentication);
        String fileName = document.summary().id() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.resource().contentLength())
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'self'")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(document.resource());
    }
}

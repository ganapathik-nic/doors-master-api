package org.gepnic.doors.masterapi.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SwaggerPageController {

    @GetMapping(value = {"/doors-swagger.html", "/swagger/doors-swagger.html"},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> doorsSwaggerPage() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/doors-swagger.html"));
    }

    @GetMapping(value = {
            "/forge-1.3.2.min.js",
            "/swagger/forge-1.3.2.min.js",
            "/doors-swagger-forge.js",
            "/swagger/doors-swagger-forge.js"
    },
            produces = "application/javascript")
    public ResponseEntity<Resource> forgeLibrary() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/vendor/forge-1.3.2.min.js"));
    }

    @GetMapping(value = {
            "/doors-swagger-template-catalogue.js",
            "/swagger/doors-swagger-template-catalogue.js"
    }, produces = "application/javascript")
    public ResponseEntity<Resource> templateCatalogueScript() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/doors-template-catalogue.js"));
    }
}

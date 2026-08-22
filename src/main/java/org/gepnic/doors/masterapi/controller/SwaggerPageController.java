package org.gepnic.doors.masterapi.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SwaggerPageController {

    @GetMapping(value = {"/doors-swagger.html", "/swagger/doors-swagger.html"},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> doorsSwaggerPage() {
        return ResponseEntity.ok(new ClassPathResource("static/doors-swagger.html"));
    }
}

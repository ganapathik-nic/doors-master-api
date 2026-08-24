package org.gepnic.doors.masterapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DOORS Master API Engine")
                        .description("Data Orchestration and On-demand Report System")
                        .version("v3.0"))
                // Always execute from the same origin that served Swagger UI.
                // This avoids mixed-content failures behind an HTTPS proxy.
                .addServersItem(new Server().url("/").description("Current DOORS API plane"))
                .addSecurityItem(new SecurityRequirement().addList("X-API-KEY"))
                .components(new Components()
                        .addSecuritySchemes("X-API-KEY", new SecurityScheme()
                                .name("X-API-KEY")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("DOORS Client Passport Authorization API Key")));
    }
}

package org.gepnic.doors.masterapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    /**
     * WebClient Builder configuration:
     * 1. 100MB Memory Buffer for heavy payload responses.
     * 2. Normal TLS trust and hostname validation using the configured JVM trust store.
     */
    @Bean
    public WebClient.Builder webClientBuilder() throws SSLException {
        HttpClient httpClient = HttpClient.create()
                .secure();

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(clientCodecConfigurer -> clientCodecConfigurer
                        .defaultCodecs()
                        .maxInMemorySize(100 * 1024 * 1024)) // 100MB
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
       
    registry.addResourceHandler("/swagger-autofill.js")
            .addResourceLocations("classpath:/static/");
}
    

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://demoetenders.tn.nic.in:*",
                        "https://demoetenders.tn.nic.in"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .exposedHeaders("X-API-KEY");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/external/**")
                .addPathPatterns("/api/v1/master/gateway/orchestrate/**")
                .addPathPatterns("/api/v1/master/reports/orchestrate/**")
                .addPathPatterns("/api/v1/master/gateway/documents/**")
                .addPathPatterns("/api/v1/master/gateway/telemetry/**")
                .addPathPatterns("/api/v1/reports/execute/**");
    }

}

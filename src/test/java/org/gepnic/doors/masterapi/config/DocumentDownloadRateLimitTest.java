package org.gepnic.doors.masterapi.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentDownloadRateLimitTest {
    private RateLimitFilter filter() {
        var properties = new RateLimitProperties();
        properties.setGatewayPathPattern("/v1/master/gateway/orchestrate/");
        properties.getRateLimit().getUi().setCapacity(1);
        properties.getRateLimit().getUi().setRefillMinutes(60);
        properties.getRateLimit().getGateway().setCapacity(1);
        properties.getRateLimit().getGateway().setRefillMinutes(60);
        return new RateLimitFilter(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/master/gateway/documents/services/tripura/download",
            "/api/v1/master/gateway/documents/policy/download"})
    void repeatedDownloadsReachTheRemainingFilterChain(String path) throws Exception {
        var filter = filter();
        for (int i = 0; i < 25; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", path), response,
                    (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result).setStatus(204));
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/master/gateway/orchestrate/test",
            "/api/v1/master/gateway/documents/services/tripura/queries/aoc",
            "/api/v1/governance/document-downloads/logs",
            "/api/v1/master/gateway/documents/services/tripura/download/extra"})
    void otherRoutesRemainLimited(String path) throws Exception {
        var filter = filter();
        filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), (r, s) -> {});
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", path), response, (r, s) -> {});
        assertThat(response.getStatus()).isEqualTo(429);
    }
}

package org.gepnic.doors.masterapi.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TrustedProxyConfigurationTest {
    @Test void stripsForwardingFromUntrustedPeer() throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("203.0.113.2");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-DOORS-MANAGER-PLANE", "forged");
        new TrustedProxyConfiguration().proxyBoundary(List.of("127.0.0.1/32")).getFilter()
                .doFilter(request, new MockHttpServletResponse(), (r,s) -> {
                    var http = (HttpServletRequest)r;
                    assertThat(http.getHeader("X-Forwarded-For")).isNull();
                    assertThat(http.getHeader("X-DOORS-MANAGER-PLANE")).isNull();
                    assertThat(TrustedProxyConfiguration.clientIp(http)).isEqualTo("203.0.113.2");
                });
    }
    @Test void ignoresForgedLeftmostIpFromAppendedProxyChain() throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.1, 203.0.113.2");
        new TrustedProxyConfiguration().proxyBoundary(List.of("127.0.0.1/32")).getFilter()
                .doFilter(request, new MockHttpServletResponse(), (r,s) ->
                    assertThat(TrustedProxyConfiguration.clientIp((HttpServletRequest)r)).isEqualTo("203.0.113.2"));
    }
}

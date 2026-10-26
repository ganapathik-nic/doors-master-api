package org.gepnic.doors.masterapi.inbound;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;
@Data @Component @ConfigurationProperties(prefix="doors.inbound")
public class InboundProperties {
    private boolean enabled=false;
    /** Development escape hatch only; non-loopback Agent URLs still require HTTPS. */
    private boolean allowLoopbackHttp=false;
    /** Optional common secret for a single-Agent/local deployment; per-Agent values take precedence. */
    private String transitSecret;
    /** Optional common Agent base URL for a single-Agent/local deployment. */
    private String agentUrl;
    private Map<String,String> agentUrls=new HashMap<>();
    private Map<String,String> agentSecrets=new HashMap<>();
}

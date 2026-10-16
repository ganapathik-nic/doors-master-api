package org.gepnic.doors.masterapi.inbound;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;
@Data @Component @ConfigurationProperties(prefix="doors.inbound")
public class InboundProperties {
    private boolean enabled=false;
    private Map<String,String> agentSecrets=new HashMap<>();
}

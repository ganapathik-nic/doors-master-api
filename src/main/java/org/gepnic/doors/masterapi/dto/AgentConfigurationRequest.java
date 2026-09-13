package org.gepnic.doors.masterapi.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.gepnic.doors.masterapi.model.Agent;

@Data
public class AgentConfigurationRequest {
    @Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String agentId;
    @NotBlank @Size(max=200) private String displayName;
    @NotBlank @Size(max=2048) private String baseUrl;
    @Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String agentInstanceCode;
    private Boolean isSandbox;
    @Pattern(regexp="INDIVIDUAL|CENTRAL") private String agentType;
    @Size(max=253) private String targetDbHost;
    @Min(1) @Max(65535) private Integer targetDbPort;
    @Size(max=100) private String targetDbName;
    @Size(max=100) private String targetDbUser;
    @Size(max=4096) private String targetDbPassword;

    public Agent toEntity() {
        java.net.URI uri = java.net.URI.create(baseUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid Agent URL");
        Agent value = new Agent();
        value.setAgentId(agentId); value.setDisplayName(displayName); value.setBaseUrl(baseUrl);
        value.setAgentInstanceCode(agentInstanceCode); value.setIsSandbox(isSandbox);
        value.setAgentType(agentType); value.setTargetDbHost(targetDbHost); value.setTargetDbPort(targetDbPort);
        value.setTargetDbName(targetDbName); value.setTargetDbUser(targetDbUser); value.setTargetDbPassword(targetDbPassword);
        return value;
    }
}

package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentTransitEncryption {
    private final String secret;
    public AgentTransitEncryption(@Value("${doors.security.agent-transit-secret:${DOORS_AGENT_TRANSIT_SECRET:}}") String secret) {
        this.secret = secret;
    }
    public String encryptPassword(String password) throws Exception {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("Configure a shared Agent transit secret of at least 32 characters");
        return EncryptionUtils.encrypt(password, secret);
    }
}

package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentDownloadPolicy;

public final class DocumentPolicyAccess {
    private DocumentPolicyAccess() {}
    public static void requireClient(DocumentDownloadPolicy policy, ApiClient client) {
        if (!Boolean.TRUE.equals(client.getIsActive()) || !"ACTIVE".equals(policy.getStatus())
                || client.getClientId() == null || policy.getAuthorizedClientIds() == null
                || !policy.getAuthorizedClientIds().isArray()) throw new SecurityException("Document policy access denied");
        for (var id : policy.getAuthorizedClientIds()) {
            if (id.asText().equals(client.getClientId().toString())) return;
        }
        throw new SecurityException("Client is not authorized for this document policy");
    }
}

package org.gepnic.doors.masterapi.dto;

public class CertificateGenerationRequest {
    private String keyId;
    private String displayName;

    // Getters and Setters
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
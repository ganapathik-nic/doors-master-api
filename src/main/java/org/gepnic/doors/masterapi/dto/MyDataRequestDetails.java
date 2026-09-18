package org.gepnic.doors.masterapi.dto;

import java.time.OffsetDateTime;
import org.gepnic.doors.masterapi.model.ExternalRequest;

/** Owner-facing response: no attachment bytes, SQL, or internal reviewer identity. */
public record MyDataRequestDetails(Long requestId, String requestTitle, String targetAgentId,
        String justification, String sampleJson, String status, String rejectionReason,
        Long queryId, OffsetDateTime createdAt, OffsetDateTime updatedAt, String attachmentName) {
    public static MyDataRequestDetails from(ExternalRequest request) {
        return new MyDataRequestDetails(request.getId(), request.getRequestTitle(),
                request.getTargetAgentId(), request.getJustification(), request.getSampleJson(),
                request.getStatus(), request.getRejectionReason(), request.getQueryId(),
                request.getCreatedAt(), request.getUpdatedAt(), request.getAttachmentName());
    }
}

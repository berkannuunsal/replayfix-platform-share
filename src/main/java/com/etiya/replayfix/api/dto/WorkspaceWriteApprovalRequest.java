package com.etiya.replayfix.api.dto;

public record WorkspaceWriteApprovalRequest(
        String approvedBy,
        String approvalId,
        String approvalNote,
        boolean acceptedGuardrails
) {
    public WorkspaceWriteApprovalRequest {
        approvedBy = approvedBy == null ? "" : approvedBy;
        approvalId = approvalId == null ? "" : approvalId;
        approvalNote = approvalNote == null ? "" : approvalNote;
    }
}

package com.etiya.replayfix.api.dto;

public record TestExecutionApprovalRequest(
        String approvedBy,
        String approvalId,
        String approvalNote,
        boolean acceptedGuardrails
) {
    public TestExecutionApprovalRequest {
        approvedBy = approvedBy == null ? "" : approvedBy;
        approvalId = approvalId == null ? "" : approvalId;
        approvalNote = approvalNote == null ? "" : approvalNote;
    }
}

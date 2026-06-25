package com.etiya.replaylab.domain;

/**
 * Human approval types required before critical operations.
 */
public enum ApprovalType {
    /**
     * Approval required before generating and applying fix.
     * Reviews: RCA analysis, proposed changes, affected files/methods
     */
    HUMAN_APPROVAL_FIX_PROPOSAL,
    
    /**
     * Approval required before creating draft PR.
     * Reviews: Test results, fix validation, commit details
     */
    HUMAN_APPROVAL_DRAFT_PR
}

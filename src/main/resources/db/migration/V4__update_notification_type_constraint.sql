-- Update notification type constraint to include AI notification types

-- Drop old auto-generated constraint
ALTER TABLE rf_notification DROP CONSTRAINT IF EXISTS CONSTRAINT_A82;

-- Add new constraint with meaningful name and all NotificationType enum values
ALTER TABLE rf_notification ADD CONSTRAINT ck_rf_notification_type
    CHECK (type IN (
        'WORKFLOW_SUCCESS',
        'WORKFLOW_PARTIAL_SUCCESS',
        'WORKFLOW_FAILED',
        'APPROVAL_REQUESTED',
        'APPROVAL_APPROVED',
        'APPROVAL_REJECTED',
        'JIRA_COMMENT_PUBLISHED',
        'RETRY_REQUIRED',
        'INTEGRATION_UNAVAILABLE',
        'AI_ANALYSIS_COMPLETED',
        'AI_ANALYSIS_FAILED',
        'AI_PROVIDER_UNAVAILABLE'
    ));

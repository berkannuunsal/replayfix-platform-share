-- Add notification system tables

CREATE TABLE rf_notification (
    id UUID PRIMARY KEY,
    case_id UUID,
    workflow_run_id UUID,
    jira_key VARCHAR(100),
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    message TEXT,
    severity VARCHAR(20),
    target_url VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP,
    delivery_attempt INT NOT NULL DEFAULT 0,
    last_delivery_error TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_notification_case ON rf_notification(case_id);
CREATE INDEX idx_notification_status ON rf_notification(status);
CREATE INDEX idx_notification_created ON rf_notification(created_at);

-- Add new fields to rf_case
ALTER TABLE rf_case ADD COLUMN application_name VARCHAR(150);
ALTER TABLE rf_case ADD COLUMN environment VARCHAR(50);
ALTER TABLE rf_case ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;

-- Add body and confidence to rf_evidence
ALTER TABLE rf_evidence ADD COLUMN body TEXT;
ALTER TABLE rf_evidence ADD COLUMN confidence DOUBLE PRECISION;

-- Add jira_preview_evidence_id to rf_workflow_run
ALTER TABLE rf_workflow_run ADD COLUMN jira_preview_evidence_id UUID;

-- Add evidence_id and sequence_number to rf_workflow_step
ALTER TABLE rf_workflow_step ADD COLUMN evidence_id UUID;
ALTER TABLE rf_workflow_step ADD COLUMN sequence_number INTEGER;

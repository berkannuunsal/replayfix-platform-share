-- Webhook Delivery Table (Idempotency Tracking)
CREATE TABLE rf_webhook_delivery (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    delivery_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    issue_key VARCHAR(100) NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL,
    case_id UUID,
    workflow_run_id UUID,
    received_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_webhook_delivery_provider_delivery UNIQUE (provider, delivery_id)
);

CREATE INDEX idx_webhook_delivery_issue ON rf_webhook_delivery(issue_key);
CREATE INDEX idx_webhook_delivery_case ON rf_webhook_delivery(case_id);

-- Workflow Run Table
CREATE TABLE rf_workflow_run (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    trigger_type VARCHAR(50) NOT NULL,
    trigger_reference VARCHAR(200),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    successful_step_count INT NOT NULL DEFAULT 0,
    failed_step_count INT NOT NULL DEFAULT 0,
    skipped_step_count INT NOT NULL DEFAULT 0,
    summary VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_workflow_run_case ON rf_workflow_run(case_id);
CREATE INDEX idx_workflow_run_status ON rf_workflow_run(status);
CREATE INDEX idx_workflow_run_created ON rf_workflow_run(created_at);

-- Workflow Step Table
CREATE TABLE rf_workflow_step (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    max_attempts INT NOT NULL DEFAULT 3,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    next_retry_at TIMESTAMP,
    evidence_type VARCHAR(50),
    evidence_source VARCHAR(200),
    error_category VARCHAR(100),
    error_message TEXT,
    result_summary VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_step_run FOREIGN KEY (workflow_run_id) 
        REFERENCES rf_workflow_run(id) ON DELETE CASCADE
);

CREATE INDEX idx_workflow_step_run ON rf_workflow_step(workflow_run_id);
CREATE INDEX idx_workflow_step_status ON rf_workflow_step(status);
CREATE INDEX idx_workflow_step_retry ON rf_workflow_step(next_retry_at);

-- Add index on rf_case for Jira key lookup
CREATE INDEX IF NOT EXISTS idx_case_jira_key ON rf_case(jira_key);

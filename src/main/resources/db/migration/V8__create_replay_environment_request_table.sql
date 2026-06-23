CREATE TABLE rf_replay_environment_request (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL
        REFERENCES rf_case(id)
        ON DELETE CASCADE,
    jira_key VARCHAR(100) NOT NULL,
    target_key VARCHAR(150) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requested_by VARCHAR(200),
    approved_by VARCHAR(200),
    approved_at TIMESTAMPTZ,
    rejected_by VARCHAR(200),
    rejected_at TIMESTAMPTZ,
    rejection_reason VARCHAR(4000),
    approval_note VARCHAR(4000),
    plan_snapshot_json TEXT,
    replay_namespace VARCHAR(200),
    proposed_host VARCHAR(500),
    dry_run_only BOOLEAN NOT NULL,
    real_provisioning_enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rf_replay_environment_request_case_created
    ON rf_replay_environment_request(case_id, created_at DESC);

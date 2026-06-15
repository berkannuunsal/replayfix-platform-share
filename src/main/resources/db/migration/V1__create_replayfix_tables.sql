CREATE TABLE rf_case (
    id UUID PRIMARY KEY,
    jira_key VARCHAR(100) NOT NULL,
    target_key VARCHAR(150) NOT NULL,
    order_id VARCHAR(200),
    trace_id VARCHAR(200),
    incident_time TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL,
    source_branch VARCHAR(200),
    source_commit VARCHAR(100),
    image_tag VARCHAR(300),
    namespace VARCHAR(200),
    generated_branch VARCHAR(300),
    pull_request_url VARCHAR(1000),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE rf_evidence (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL
        REFERENCES rf_case(id)
        ON DELETE CASCADE,
    evidence_type VARCHAR(50) NOT NULL,
    source VARCHAR(200) NOT NULL,
    content_text TEXT,
    content_location VARCHAR(1000),
    content_hash VARCHAR(128),
    sanitized BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rf_evidence_case
    ON rf_evidence(case_id, created_at);

CREATE TABLE rf_audit_event (
    id UUID PRIMARY KEY,
    case_id UUID
        REFERENCES rf_case(id)
        ON DELETE CASCADE,
    action VARCHAR(150) NOT NULL,
    actor VARCHAR(150) NOT NULL,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rf_audit_case
    ON rf_audit_event(case_id, created_at);

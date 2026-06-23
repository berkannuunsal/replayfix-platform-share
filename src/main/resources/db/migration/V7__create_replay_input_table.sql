CREATE TABLE rf_replay_input (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL
        REFERENCES rf_case(id)
        ON DELETE CASCADE,
    jira_key VARCHAR(100) NOT NULL,
    target_key VARCHAR(150) NOT NULL,
    endpoint_path VARCHAR(1000),
    http_method VARCHAR(20),
    sanitized_headers_json TEXT,
    sanitized_request_body_json TEXT,
    sanitized_query_params_json TEXT,
    trace_id VARCHAR(200),
    order_id VARCHAR(200),
    customer_id VARCHAR(200),
    account_id VARCHAR(200),
    business_key VARCHAR(300),
    source VARCHAR(50),
    sanitized BOOLEAN NOT NULL,
    contains_secrets BOOLEAN NOT NULL,
    contains_personal_data BOOLEAN NOT NULL,
    sanitization_warnings_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rf_replay_input_case_created
    ON rf_replay_input(case_id, created_at DESC);

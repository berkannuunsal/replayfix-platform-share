CREATE TABLE rf_replay_component_hint (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL
        REFERENCES rf_case(id)
        ON DELETE CASCADE,
    jira_key VARCHAR(100) NOT NULL,
    target_key VARCHAR(150) NOT NULL,
    component_key VARCHAR(150) NOT NULL,
    requested_mode VARCHAR(50) NOT NULL,
    reason TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rf_replay_component_hint_case_created
    ON rf_replay_component_hint(case_id, created_at DESC);

CREATE INDEX idx_rf_replay_component_hint_case_component
    ON rf_replay_component_hint(case_id, component_key);

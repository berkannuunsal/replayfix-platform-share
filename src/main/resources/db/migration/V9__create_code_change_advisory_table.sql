create table if not exists rf_code_change_advisory (
    id uuid primary key,
    case_id uuid not null,
    jira_key varchar(100) not null,
    target_key varchar(150) not null,
    advisory_mode varchar(50) not null,
    llm_used boolean not null,
    llm_status varchar(100) not null,
    status varchar(50) not null,
    confidence varchar(100),
    recommended_file varchar(1000),
    recommended_method_name varchar(300),
    recommended_change_type varchar(200),
    recommended_description text,
    recommended_pseudo_patch text,
    risks_json text,
    missing_evidence_json text,
    test_suggestions_json text,
    should_proceed_to_patch boolean not null,
    deterministic_fallback_reason varchar(200),
    safe_prompt_summary_json text,
    response_snapshot_json text,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index if not exists idx_rf_code_change_advisory_case_created
    on rf_code_change_advisory(case_id, created_at desc);

create index if not exists idx_rf_code_change_advisory_case_mode_created
    on rf_code_change_advisory(case_id, advisory_mode, created_at desc);

-- Jira Comment Publication Table
CREATE TABLE rf_jira_comment_publication (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    issue_key VARCHAR(100) NOT NULL,
    preview_evidence_id UUID NOT NULL,
    approval_id UUID,
    content_sha256 VARCHAR(64) NOT NULL,
    jira_comment_id VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    error_category VARCHAR(100),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_jira_comment_publication UNIQUE (issue_key, content_sha256)
);

CREATE INDEX idx_jira_comment_publication_case ON rf_jira_comment_publication(case_id);
CREATE INDEX idx_jira_comment_publication_status ON rf_jira_comment_publication(status);
CREATE INDEX idx_jira_comment_publication_preview ON rf_jira_comment_publication(preview_evidence_id);

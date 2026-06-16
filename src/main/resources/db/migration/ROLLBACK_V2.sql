-- Rollback script for V2__create_webhook_workflow_tables.sql
-- Run this manually if you need to rollback the webhook/workflow tables

-- Drop indexes first
DROP INDEX IF EXISTS idx_case_jira_key;
DROP INDEX IF EXISTS idx_workflow_step_retry;
DROP INDEX IF EXISTS idx_workflow_step_status;
DROP INDEX IF EXISTS idx_workflow_step_run;
DROP INDEX IF EXISTS idx_workflow_run_created;
DROP INDEX IF EXISTS idx_workflow_run_status;
DROP INDEX IF EXISTS idx_workflow_run_case;
DROP INDEX IF EXISTS idx_webhook_delivery_case;
DROP INDEX IF EXISTS idx_webhook_delivery_issue;

-- Drop tables in reverse order (respecting foreign keys)
DROP TABLE IF EXISTS rf_workflow_step;
DROP TABLE IF EXISTS rf_workflow_run;
DROP TABLE IF EXISTS rf_webhook_delivery;

-- Note: This is a manual rollback script
-- Flyway does not support automatic rollback in community edition

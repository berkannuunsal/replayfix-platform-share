# Database Migration V2 - Webhook & Workflow Tables

## Overview

This migration adds support for Jira webhook automation and workflow orchestration.

## New Tables

### 1. `rf_webhook_delivery` (Idempotency Tracking)
- Tracks all webhook deliveries to prevent duplicate processing
- Uses `provider + delivery_id` as unique constraint
- Falls back to `provider + issue_key + event_type + body_sha256` for idempotency

### 2. `rf_workflow_run` (Workflow Metadata)
- Tracks each workflow execution triggered by webhooks or manual actions
- Contains aggregated step counts and final status
- Links to case via `case_id`

### 3. `rf_workflow_step` (Step Details)
- Individual steps within a workflow run
- Supports retry with backoff
- Tracks evidence references and error categories

### 4. Index Addition
- `idx_case_jira_key` on `rf_case(jira_key)` for fast case lookup

## Migration Steps

### Automatic Migration (Spring Boot Startup)

When Flyway is enabled (default), migration runs automatically on startup:

```bash
# Set database connection
export REPLAYLAB_DB_URL=jdbc:postgresql://localhost:5433/replaylab
export REPLAYLAB_DB_USERNAME=replaylab
export REPLAYLAB_DB_PASSWORD=replaylab

# Start application - migration runs automatically
./mvnw spring-boot:run
```

### Manual Migration (psql)

If you need to run migration manually:

```bash
psql -h localhost -p 5433 -U replaylab -d replaylab -f src/main/resources/db/migration/V2__create_webhook_workflow_tables.sql
```

### Verify Migration

```sql
-- Check Flyway schema history
SELECT * FROM flyway_schema_history ORDER BY installed_rank;

-- Verify tables exist
\dt rf_webhook_*
\dt rf_workflow_*

-- Check indexes
\di idx_webhook_*
\di idx_workflow_*
\di idx_case_jira_key

-- Check table comments
SELECT 
    schemaname, 
    tablename, 
    pg_catalog.obj_description(c.oid, 'pg_class') AS table_comment
FROM pg_tables t
JOIN pg_class c ON t.tablename = c.relname
WHERE schemaname = 'public' 
    AND tablename LIKE 'rf_w%'
ORDER BY tablename;
```

## Rollback (If Needed)

⚠️ **WARNING: This will delete all webhook and workflow data!**

```bash
psql -h localhost -p 5433 -U replaylab -d replaylab -f src/main/resources/db/migration/ROLLBACK_V2.sql
```

After rollback, you must manually delete the Flyway history entry:

```sql
DELETE FROM flyway_schema_history WHERE version = '2';
```

## Data Verification Queries

### Check Webhook Deliveries
```sql
SELECT 
    provider,
    delivery_id,
    event_type,
    issue_key,
    status,
    received_at
FROM rf_webhook_delivery
ORDER BY received_at DESC
LIMIT 10;
```

### Check Workflow Runs
```sql
SELECT 
    wr.id,
    wr.case_id,
    rc.jira_key,
    wr.trigger_type,
    wr.status,
    wr.successful_step_count,
    wr.failed_step_count,
    wr.created_at,
    wr.finished_at
FROM rf_workflow_run wr
JOIN rf_case rc ON wr.case_id = rc.id
ORDER BY wr.created_at DESC
LIMIT 10;
```

### Check Workflow Steps
```sql
SELECT 
    ws.step_name,
    ws.status,
    ws.attempt,
    ws.started_at,
    ws.finished_at,
    ws.error_category,
    ws.result_summary
FROM rf_workflow_step ws
WHERE workflow_run_id = 'YOUR_RUN_ID'
ORDER BY ws.started_at NULLS FIRST;
```

### Workflow Performance Analysis
```sql
SELECT 
    ws.step_name,
    COUNT(*) as execution_count,
    AVG(EXTRACT(EPOCH FROM (ws.finished_at - ws.started_at))) as avg_duration_seconds,
    SUM(CASE WHEN ws.status = 'SUCCESS' THEN 1 ELSE 0 END) as success_count,
    SUM(CASE WHEN ws.status = 'FAILED' THEN 1 ELSE 0 END) as failure_count
FROM rf_workflow_step ws
WHERE ws.finished_at IS NOT NULL
GROUP BY ws.step_name
ORDER BY avg_duration_seconds DESC;
```

## Schema Documentation

### Workflow Status Values
- `PENDING` - Workflow created, not started
- `RUNNING` - Currently executing
- `PARTIAL_SUCCESS` - Some optional steps failed
- `SUCCESS` - All required steps succeeded
- `FAILED` - Required step(s) failed
- `CANCELLED` - Manually cancelled

### Step Status Values
- `PENDING` - Not yet started
- `RUNNING` - Currently executing
- `SUCCESS` - Completed successfully
- `SKIPPED` - Conditionally skipped (e.g., integration disabled)
- `FAILED` - Execution failed
- `RETRY_WAITING` - Failed but will retry

### Error Categories
- `AUTHENTICATION_ERROR` - 401, invalid credentials (not retryable)
- `AUTHORIZATION_ERROR` - 403, insufficient permissions (not retryable)
- `RATE_LIMIT` - 429, rate limited (retryable)
- `UPSTREAM_UNAVAILABLE` - 502/503/504, service unavailable (retryable)
- `TIMEOUT` - Request timeout (retryable)
- `UNKNOWN_ERROR` - Unclassified error

## Next Steps

After migration succeeds:

1. ✅ Verify all 3 tables exist
2. ✅ Check indexes are created
3. ✅ Restart application to load new entities
4. ✅ Test webhook endpoint: `POST /api/v1/webhooks/jira`
5. ✅ Test workflow API: `GET /api/v1/workflows/{runId}`

## Troubleshooting

### Migration Fails with "relation already exists"

Tables might exist from previous attempt. Check:

```sql
SELECT tablename FROM pg_tables WHERE tablename LIKE 'rf_w%';
```

If tables exist, either:
- Run rollback script first
- Or manually drop tables and retry

### Permission Errors

Ensure PostgreSQL user has necessary permissions:

```sql
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO replaylab;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO replaylab;
```

### Flyway Checksum Mismatch

If migration file was modified after initial run:

```sql
-- Repair Flyway history (use with caution!)
SELECT * FROM flyway_schema_history WHERE version = '2';
-- If checksum is wrong, delete and re-run
DELETE FROM flyway_schema_history WHERE version = '2';
```

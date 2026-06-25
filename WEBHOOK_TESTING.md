# Jira Webhook Testing Guide

## Prerequisites

1. Set environment variables:
```bash
export REPLAYLAB_JIRA_WEBHOOK_ENABLED=true
export JIRA_WEBHOOK_SECRET=LOCAL_TEST_SECRET
```

2. Start the application:
```bash
./mvnw spring-boot:run
```

## Manual Testing

### Test Webhook Acceptance

```bash
curl -s -X POST \
  "http://localhost:8088/api/v1/webhooks/jira" \
  -H "Content-Type: application/json" \
  -H "X-ReplayLab-Webhook-Secret: LOCAL_TEST_SECRET" \
  -H "X-Atlassian-Webhook-Identifier: local-test-001" \
  --data-binary @jira-webhook-sample.json \
  -o jira-webhook-result.json

notepad jira-webhook-result.json
```

Expected response:
```json
{
  "accepted": true,
  "duplicate": false,
  "deliveryId": "local-test-001",
  "issueKey": "FIZZMS-8346",
  "caseId": "...",
  "workflowRunId": "...",
  "workflowStatus": "PENDING",
  "warnings": []
}
```

### Test Duplicate Detection

Run the same curl command twice. Second response should have:
```json
{
  "accepted": true,
  "duplicate": true,
  ...
}
```

### View Workflow Status

```bash
curl -s "http://localhost:8088/api/v1/workflows/{WORKFLOW_RUN_ID}" -o workflow-result.json
notepad workflow-result.json
```

### View Case Workflows

```bash
curl -s "http://localhost:8088/api/v1/workflows/cases/{CASE_ID}" -o case-workflows.json
notepad case-workflows.json
```

## Database Verification

### Check Webhook Deliveries

```sql
SELECT *
FROM rf_webhook_delivery
ORDER BY received_at DESC;
```

### Check Workflow Runs

```sql
SELECT *
FROM rf_workflow_run
WHERE case_id = '{CASE_ID}'
ORDER BY created_at DESC;
```

### Check Workflow Steps

```sql
SELECT *
FROM rf_workflow_step
WHERE workflow_run_id = '{RUN_ID}'
ORDER BY started_at NULLS FIRST;
```

### Check Audit Events

```sql
SELECT *
FROM rf_audit_event
WHERE case_id = '{CASE_ID}'
ORDER BY event_time DESC;
```

## Security Testing

### Test Missing Secret

```bash
curl -X POST \
  "http://localhost:8088/api/v1/webhooks/jira" \
  -H "Content-Type: application/json" \
  --data-binary @jira-webhook-sample.json
```

Expected: HTTP 403 Forbidden

### Test Invalid Secret

```bash
curl -X POST \
  "http://localhost:8088/api/v1/webhooks/jira" \
  -H "Content-Type: application/json" \
  -H "X-ReplayLab-Webhook-Secret: WRONG_SECRET" \
  --data-binary @jira-webhook-sample.json
```

Expected: HTTP 403 Forbidden

### Test Body Size Limit

```bash
# Create a large file (> 1MB)
dd if=/dev/zero of=large-payload.json bs=1M count=2

curl -X POST \
  "http://localhost:8088/api/v1/webhooks/jira" \
  -H "Content-Type: application/json" \
  -H "X-ReplayLab-Webhook-Secret: LOCAL_TEST_SECRET" \
  --data-binary @large-payload.json
```

Expected: HTTP 403 Forbidden

## Configuration Testing

### Disable Webhook

```bash
export REPLAYLAB_JIRA_WEBHOOK_ENABLED=false
# Restart application

curl -X POST \
  "http://localhost:8088/api/v1/webhooks/jira" \
  -H "Content-Type: application/json" \
  -H "X-ReplayLab-Webhook-Secret: LOCAL_TEST_SECRET" \
  --data-binary @jira-webhook-sample.json
```

Expected: HTTP 403 with "Jira webhook is disabled"

### Test Project Allowlist

```bash
export REPLAYLAB_JIRA_WEBHOOK_ALLOWED_PROJECTS=FIZZMS,OTHERPROJECT
# Restart application and test
```

### Test Issue Type Allowlist

```bash
export REPLAYLAB_JIRA_WEBHOOK_ALLOWED_ISSUE_TYPES=Bug,Defect
# Restart application and test
```

## Expected Behavior

1. ✅ Webhook validated for secret, body size, event type, project, issue type
2. ✅ Duplicate deliveries return existing case/workflow IDs
3. ✅ New case created if Jira key not found
4. ✅ Existing case reused if Jira key exists
5. ✅ Workflow run created with PENDING status
6. ✅ 15 workflow steps created as PENDING
7. ✅ Workflow execution starts async
8. ✅ Steps execute sequentially (POC: stubbed)
9. ✅ Audit events recorded for all actions
10. ✅ No destructive operations executed

## Notes

- POC Implementation: Workflow steps are stubbed and complete immediately
- In production: Each step would call actual integration services
- Async execution prevents webhook timeout
- Retry logic configured but not fully implemented in POC

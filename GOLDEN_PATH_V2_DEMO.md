# Golden Path V2 - Friday Demo Execution Guide

## Demo Scenario: Real Incident Analysis with Rovo Integration

**Target:** FIZZMS-8346 (or NTF build 309 backup)
**Branch:** test2
**Fix Branch:** bugfix/FIZZMS-8346-replayfix

## Prerequisites

1. ✅ Application running on port 8088
2. ✅ Database with V4 migration applied (notification constraint fix)
3. ✅ Integration credentials configured
4. ✅ Real case data available

## Phase 1: Evidence Collection & Rovo Integration

### Step 1: Execute Golden Path

```powershell
# Execute golden path to collect all evidence
$response = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$response | ConvertTo-Json -Depth 10

# Save case ID
$caseId = $response.caseId
Write-Host "Case ID: $caseId"

# Check dashboard
$dashboardUrl = "http://localhost:8088" + $response.steps.'7_summary'.dashboardUrl
Write-Host "Dashboard: $dashboardUrl"
Start-Process $dashboardUrl
```

**Expected Result:**
```json
{
  "jiraKey": "FIZZMS-8346",
  "targetKey": "fizz-marketplace-service",
  "caseId": "uuid",
  "status": "SUCCESS",
  "synthetic": false,
  "steps": {
    "1_case_resolution": { "result": "SUCCESS" },
    "2_jira_evidence": { "result": "SUCCESS", "count": 1 },
    "3_jenkins_evidence": { "result": "SUCCESS", "count": 1 },
    "4_loki_evidence": { "result": "FOUND_EXISTING", "count": 5 },
    "5_tempo_evidence": { "result": "FOUND_EXISTING" },
    "6_deterministic_rca": { "result": "SUCCESS", "confidence": 0.85 },
    "7_summary": {
      "totalEvidence": 15,
      "missingEvidence": [],
      "synthetic": false
    }
  }
}
```

### Step 2: Rovo Gets Context (Simulated)

```powershell
# This endpoint would be called by Rovo Forge Action
$rovoContext = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/rovo/incidents/FIZZMS-8346/context" -Method Get
$rovoContext | ConvertTo-Json -Depth 10
```

**Expected Response:**
```json
{
  "caseId": "uuid",
  "jiraKey": "FIZZMS-8346",
  "jiraSummary": "Authentication failure in marketplace",
  "applicationKey": "fizz-marketplace-service",
  "targetKey": "fizz-marketplace-service",
  "lokiFindings": {
    "totalLogEntries": 125,
    "errorSignals": ["AuthenticationException", "TokenExpired"],
    "warningSignals": ["Slow response"],
    "queryUsed": "Loki query",
    "timeRange": "Last 24 hours"
  },
  "jenkinsContext": {
    "jobName": "MODERNIZATION.FIZZ_MARKETPLACE_BUILD",
    "buildNumber": 456,
    "commitSha": "abc123...",
    "branch": "test2",
    "buildStatus": "SUCCESS"
  },
  "bitbucketContext": {
    "projectKey": "ETIYA",
    "repositorySlug": "fizz-marketplace",
    "defaultBranch": "test2",
    "analysisTargetBranch": "test2"
  },
  "deterministicRca": {
    "summary": "Token validation logic failure",
    "probableRootCause": "Missing expiry check",
    "confidence": 0.85,
    "source": "deterministic-root-cause-jenkins-validated"
  },
  "missingEvidence": [],
  "synthetic": false
}
```

### Step 3: Rovo Submits RCA Analysis (Simulated)

```powershell
# This would be called by Rovo after analysis
$rovoRca = @{
    executiveSummary = "Authentication token validation fails due to missing expiry check in AuthService.validateToken method"
    probableFailureChain = @(
        "User request arrives",
        "AuthService.validateToken() called",
        "Token expiry not checked",
        "Expired token accepted",
        "Downstream service rejects",
        "Authentication failure"
    )
    probableRootCause = "Missing token expiry validation in AuthService.validateToken() method introduced in commit abc123"
    impactedComponent = "fizz-marketplace-service/AuthService"
    confidence = 0.87
    competingHypotheses = @(
        @{
            hypothesis = "Database connection timeout"
            likelihood = 0.15
            supportingEvidence = "Some slow query logs"
            contradictingEvidence = "No DB errors in logs"
        }
    )
    similarIncidents = @(
        @{
            jiraKey = "FIZZMS-8200"
            summary = "Token validation issue"
            similarity = 0.75
            resolution = "Added token expiry check"
        }
    )
    confluenceReferences = @("https://confluence.../auth-patterns")
    regressionTestHypothesis = "Test with expired token should fail validation"
    minimumFixDirection = "Add token expiry check before validation"
    missingEvidence = @()
    recommendedNextAction = "Review commit abc123 and add expiry validation"
}

$rovoRcaJson = $rovoRca | ConvertTo-Json -Depth 10
Invoke-RestMethod -Uri "http://localhost:8088/api/v1/rovo/incidents/FIZZMS-8346/analysis" `
    -Method Post `
    -Body $rovoRcaJson `
    -ContentType "application/json"
```

**Expected:** `202 Accepted` - Rovo RCA persisted as evidence

### Step 4: Verify Rovo RCA Evidence

```sql
-- Check Rovo RCA evidence
SELECT 
    id,
    evidence_type,
    source,
    confidence,
    created_at,
    LENGTH(content_text) as content_length
FROM rf_evidence
WHERE case_id = '<case-uuid>'
  AND evidence_type = 'ROVO_RCA';
```

## Phase 2: Method Commit History Analysis (Future Implementation)

```powershell
# This will be implemented in next phase
# POST /api/v1/cases/{caseId}/method-history/analyze
# {
#   "repository": "etiya/fizz-marketplace",
#   "branch": "test2",
#   "filePath": "src/main/java/com/etiya/fizz/AuthService.java",
#   "className": "AuthService",
#   "methodName": "validateToken",
#   "lookbackDays": 45
# }
```

## Phase 3: Fix Workflow (Future Implementation)

```powershell
# Start fix workflow
$fixWorkflow = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/fix-workflow/start" -Method Post
$approvalId = $fixWorkflow.approvalId
Write-Host "Awaiting fix proposal approval: $approvalId"

# Human reviews proposal and approves
Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/fix-workflow/approve-fix/$approvalId" -Method Post

# System generates fix, runs tests, requests PR approval
Start-Sleep -Seconds 30

# Check status
$status = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/fix-workflow/status" -Method Get
$status | ConvertTo-Json -Depth 10

# If tests passed, approve PR creation
$prApprovalId = $status.pendingApprovals[0].id
Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/fix-workflow/approve-pr/$prApprovalId" -Method Post

# Get final status with PR URL
$finalStatus = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/fix-workflow/status" -Method Get
Write-Host "Draft PR Created: $($finalStatus.pullRequestUrl)"
```

**Expected Fix Workflow Output:**
```json
{
  "stage": "COMPLETED",
  "branchName": "bugfix/FIZZMS-8346-replayfix",
  "sourceCommitSha": "origin/test2 HEAD",
  "fixCommitSha": "xyz789...",
  "testResults": {
    "compiled": true,
    "testsRun": 15,
    "testsPassed": 15,
    "testsFailed": 0
  },
  "pullRequest": {
    "url": "https://bitbucket.etiya.com/projects/ETIYA/repos/fizz-marketplace/pull-requests/123",
    "status": "DRAFT",
    "sourceBranch": "bugfix/FIZZMS-8346-replayfix",
    "targetBranch": "test2",
    "author": "replayfix-bot",
    "created": "2026-06-17T09:00:00Z"
  },
  "approvals": [
    {
      "type": "HUMAN_APPROVAL_FIX_PROPOSAL",
      "approved": true,
      "approvedBy": "user@etiya.com",
      "approvedAt": "2026-06-17T08:55:00Z"
    },
    {
      "type": "HUMAN_APPROVAL_DRAFT_PR",
      "approved": true,
      "approvedBy": "user@etiya.com",
      "approvedAt": "2026-06-17T08:58:00Z"
    }
  ],
  "changedFiles": [
    "src/main/java/com/etiya/fizz/AuthService.java",
    "src/test/java/com/etiya/fizz/AuthServiceTest.java"
  ],
  "methodsChanged": [
    "AuthService.validateToken"
  ]
}
```

## Verification Checklist

### Evidence Collection (Phase 1)
- [ ] Jira issue: FIZZMS-8346 (REAL)
- [ ] Jira evidence collected
- [ ] Jenkins evidence collected (real build, real commit SHA)
- [ ] Loki evidence available
- [ ] Tempo evidence available (or marked unavailable)
- [ ] Deterministic RCA generated
- [ ] synthetic=false throughout
- [ ] No DEMO or SYNTHETIC labels on dashboard

### Rovo Integration (Phase 1)
- [ ] GET /api/v1/rovo/incidents/{jiraKey}/context returns sanitized context
- [ ] Context includes all evidence types
- [ ] Context includes Jenkins commit SHA
- [ ] Context includes Bitbucket repository info
- [ ] Context target branch = test2
- [ ] POST /api/v1/rovo/incidents/{jiraKey}/analysis persists RCA
- [ ] Rovo RCA stored as ROVO_RCA evidence
- [ ] Rovo RCA source = "rovo-incident-commander"
- [ ] Rovo RCA confidence stored

### Method History (Phase 2 - Future)
- [ ] Repository: Real Bitbucket repo
- [ ] Branch: test2
- [ ] File/Class/Method: Real source code
- [ ] Last 45 days commits retrieved
- [ ] Commits filtered to method changes only
- [ ] Commit risk scores calculated
- [ ] Suspected commit identified
- [ ] METHOD_COMMIT_HISTORY evidence stored
- [ ] Dashboard shows timeline

### Fix Workflow (Phase 3 - Future)
- [ ] Fix proposal requested
- [ ] Human approval received
- [ ] Branch created from origin/test2 HEAD
- [ ] Branch name: bugfix/FIZZMS-8346-replayfix
- [ ] Regression test generated
- [ ] Minimum fix applied
- [ ] Compilation successful
- [ ] Tests passed
- [ ] PR approval requested
- [ ] Human approval received
- [ ] Branch pushed to origin
- [ ] Draft PR created
- [ ] PR target: test2
- [ ] PR status: DRAFT
- [ ] PR description complete
- [ ] NO merge performed
- [ ] NO deployment performed

## Database Queries for Verification

```sql
-- Case info
SELECT 
    id, jira_key, target_key, application_name,
    status, jenkins_job_name, jenkins_build_number,
    source_commit, created_at
FROM rf_case
WHERE jira_key = 'FIZZMS-8346';

-- All evidence
SELECT 
    evidence_type,
    source,
    confidence,
    COUNT(*) as count,
    MAX(created_at) as latest
FROM rf_evidence
WHERE case_id = '<case-uuid>'
GROUP BY evidence_type, source, confidence
ORDER BY latest DESC;

-- Rovo RCA
SELECT 
    content_text,
    confidence,
    source,
    created_at
FROM rf_evidence
WHERE case_id = '<case-uuid>'
  AND evidence_type = 'ROVO_RCA';

-- Method commit history (future)
SELECT 
    content_text,
    source,
    created_at
FROM rf_evidence
WHERE case_id = '<case-uuid>'
  AND evidence_type = 'METHOD_COMMIT_HISTORY';
```

## Demo Talking Points

### Current Implementation (Phase 1)
1. **Real Evidence Collection**
   - "ReplayFix collects real evidence from production incident"
   - "Jira, Loki, Jenkins, Bitbucket all queried with real credentials"
   - "No synthetic or demo data"

2. **Rovo Integration Contract**
   - "Two endpoints for Rovo Incident Commander integration"
   - "GET endpoint provides sanitized context"
   - "POST endpoint receives Rovo's AI analysis"
   - "Rovo RCA stored as evidence for downstream use"

3. **Evidence Correlation**
   - "Jenkins commit SHA matches Bitbucket checkout"
   - "Loki logs correlated with incident time"
   - "Tempo traces show service chain"
   - "All evidence timestamped and linked"

### Future Implementation (Phase 2-3)
4. **Method Commit History**
   - "45-day lookback on specific methods"
   - "Risk scoring based on timing and symptoms"
   - "Suspected commit identification"
   - "Timeline visualization on dashboard"

5. **Automated Fix with Approvals**
   - "Two-stage human approval workflow"
   - "Branch created from exact test2 HEAD"
   - "Tests must pass before PR"
   - "Draft PR only - human review required"

6. **Safety Guarantees**
   - "No automatic merge"
   - "No automatic deployment"
   - "Human approval at every critical step"
   - "Full rollback capability"

## Success Metrics

### Phase 1 (Current)
- ✅ Real evidence collected
- ✅ Rovo integration contracts defined
- ✅ Evidence correlation working
- ✅ synthetic=false throughout
- ✅ Dashboard shows real data

### Phase 2-3 (Next Sprint)
- ⏳ Method history analyzed
- ⏳ Suspected commit identified
- ⏳ Fix workflow automated
- ⏳ Draft PR created
- ⏳ All approvals tracked

## Next Steps After Demo

1. **Immediate (This Week)**
   - Test Phase 1 with FIZZMS-8346
   - Verify Rovo integration contracts
   - Collect real evidence
   - Generate demo screenshots

2. **Next Sprint**
   - Implement MethodCommitHistoryService
   - Implement ApprovalWorkflowService
   - Implement FixGenerationWorkflowService
   - Add dashboard method timeline

3. **Integration Testing**
   - Rovo Forge Action development
   - End-to-end workflow testing
   - Performance optimization
   - Error handling hardening

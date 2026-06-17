# Golden Path Demo - Real Incident End-to-End Flow

## Overview
This document describes the Golden Path orchestration for Friday's demo.
**NO WRITE operations** to external systems (Jira, Git, Jenkins, Kubernetes).

## Prerequisites
1. Application running on port 8088
2. Database with real incident case data
3. Integration credentials configured (Jira, Jenkins, Loki, Tempo, Bitbucket)

## Primary Test Cases

### Option 1: FIZZMS-8346
```bash
POST http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service
```

### Option 2: NTF Build 309
```bash
POST http://localhost:8088/api/v1/golden-path/execute?jiraKey=<NTF_JIRA_KEY>&targetKey=<NTF_TARGET_KEY>
```

## Execution Flow

The Golden Path endpoint orchestrates:

1. **Case Resolution** - Find existing or create new case
2. **Jira Evidence** - Collect real Jira issue details
3. **Jenkins Evidence** - Collect build, commit SHA, job info
4. **Loki Evidence** - Check for log evidence (may require prior collection)
5. **Tempo Evidence** - Check for trace evidence (optional)
6. **Deterministic RCA** - Generate root cause analysis from real evidence
7. **Summary** - Comprehensive evidence inventory

## PowerShell Commands

### Execute Golden Path
```powershell
$response = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$response | ConvertTo-Json -Depth 10
```

### Get Case Details
```powershell
$caseId = $response.caseId
Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId" -Method Get | ConvertTo-Json -Depth 10
```

### Get Dashboard URL
```powershell
$dashboardUrl = "http://localhost:8088" + $response.steps.'7_summary'.dashboardUrl
Write-Host "Dashboard: $dashboardUrl"
```

### Get Evidence by Type
```powershell
Invoke-RestMethod -Uri "http://localhost:8088/api/v1/cases/$caseId/evidence" -Method Get | ConvertTo-Json -Depth 10
```

## Expected Output Structure

```json
{
  "jiraKey": "FIZZMS-8346",
  "targetKey": "fizz-marketplace-service",
  "timestamp": "2026-06-17T...",
  "caseId": "uuid-here",
  "status": "SUCCESS",
  "synthetic": false,
  "steps": {
    "1_case_resolution": {
      "action": "FOUND_EXISTING" | "CREATED_NEW",
      "caseId": "uuid",
      "status": "EVIDENCE_COLLECTION",
      "result": "SUCCESS"
    },
    "2_jira_evidence": {
      "result": "SUCCESS",
      "count": 1,
      "evidenceIds": ["uuid"]
    },
    "3_jenkins_evidence": {
      "result": "SUCCESS",
      "count": 1,
      "evidenceIds": ["uuid"]
    },
    "4_loki_evidence": {
      "result": "FOUND_EXISTING" | "UNAVAILABLE",
      "count": 5,
      "evidenceIds": ["uuid1", "uuid2"]
    },
    "5_tempo_evidence": {
      "result": "FOUND_EXISTING" | "UNAVAILABLE"
    },
    "6_deterministic_rca": {
      "result": "SUCCESS",
      "evidenceId": "uuid",
      "confidence": 0.85,
      "source": "deterministic-root-cause-jenkins-validated",
      "synthetic": false
    },
    "7_summary": {
      "caseId": "uuid",
      "jiraKey": "FIZZMS-8346",
      "status": "EVIDENCE_COLLECTION",
      "totalEvidence": 15,
      "evidenceByType": {
        "JIRA_ISSUE": 1,
        "JENKINS_BUILD": 1,
        "LOKI_LOGS": 5,
        "TEMPO_TRACE": 3,
        "ROOT_CAUSE_ANALYSIS": 1
      },
      "synthetic": false,
      "dashboardUrl": "/replayfix/?caseId=uuid",
      "missingEvidence": []
    }
  }
}
```

## Verification Checklist

- [ ] Jira issue is REAL (not demo/synthetic)
- [ ] Loki evidence is REAL
- [ ] Jenkins build is REAL
- [ ] Bitbucket repository is REAL
- [ ] Jenkins/Bitbucket/checkout commit SHAs match
- [ ] Source file/class/method shown
- [ ] Deterministic RCA references real evidence IDs
- [ ] Dashboard shows NO "DEMO" or "SYNTHETIC" labels
- [ ] Missing evidence honestly listed
- [ ] NO write operations occurred

## Troubleshooting

### Case Not Found
- Check database: `SELECT * FROM rf_case WHERE jira_key = 'FIZZMS-8346';`
- Create manually if needed

### Evidence Collection Failed
- Check integration configurations in application.yml
- Verify credentials
- Check logs for specific error messages

### RCA Not Generated
- Verify minimum evidence exists (JIRA + Jenkins + AI Input Bundle)
- Check `rf_evidence` table for AI_INPUT_BUNDLE
- May need to run `POST /api/v1/cases/{caseId}/collect-context` first

## Database Queries

```sql
-- Check case
SELECT 
    id, jira_key, target_key, application_name, 
    status, jenkins_job_name, jenkins_build_number
FROM rf_case 
WHERE jira_key = 'FIZZMS-8346';

-- Check evidence by type
SELECT 
    evidence_type, 
    source, 
    confidence,
    created_at 
FROM rf_evidence 
WHERE case_id = '<case-uuid>'
ORDER BY evidence_type, created_at DESC;

-- Check Jenkins commit SHA
SELECT 
    content_text 
FROM rf_evidence 
WHERE case_id = '<case-uuid>' 
  AND evidence_type = 'JENKINS_BUILD';

-- Check deterministic RCA
SELECT 
    content_text,
    confidence,
    source
FROM rf_evidence
WHERE case_id = '<case-uuid>'
  AND evidence_type = 'ROOT_CAUSE_ANALYSIS'
  AND source LIKE '%deterministic%';
```

## Notes

- This is READ-ONLY orchestration
- Safe for demo environment
- No Git push, PR, Jenkins trigger, deployment
- No Jira comment publishing
- No Kubernetes writes

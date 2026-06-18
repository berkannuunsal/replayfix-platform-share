# Repository Resolution & Jenkins Contract Fix

## Problem
Repository resolution evidence was being persisted successfully but JenkinsEvidenceCollectorService failed with:
```
Primary repository was not resolved.
```

**Root Cause:** Contract mismatch between evidence producer and consumer.

### Evidence Producer (RepositoryResolutionEvidenceService)
Was writing:
```json
{
  "bitbucketProjectKey": "DCE",
  "repositorySlug": "backend",
  "repositoryName": "backend",
  "cloneUrl": "https://...",
  "sourceBranch": "test2"
}
```

### Evidence Consumer (JenkinsEvidenceCollectorService)
Was expecting `RepositoryResolutionResult`:
```java
record RepositoryResolutionResult(
    String projectKey,
    String primaryRepositorySlug,  // ← MISSING in evidence!
    List<RepositoryCandidate> candidates,
    List<String> unresolvedSignals,
    String warning
)
```

## Solution

### 1. Canonical Evidence Model
Updated `RepositoryResolutionEvidenceService` to write **both** canonical and backward-compatible fields:

**Canonical Fields (for RepositoryResolutionResult):**
```json
{
  "projectKey": "DCE",
  "primaryRepositorySlug": "backend",
  "candidates": [],
  "unresolvedSignals": [],
  "warning": ""
}
```

**Extended Fields:**
```json
{
  "applicationKey": "backend",
  "targetKey": "backend",
  "repositoryName": "backend",
  "sanitizedCloneUrl": "https://bitbucket.etiya.com/scm/dce/backend.git",
  "sourceBranch": "test2",
  "confidence": 1.0,
  "matchedSignals": ["EXACT_TARGET_CONFIG_MATCH"],
  "resolutionMethod": "TARGET_CONFIG_EXACT_MATCH",
  "repositoryState": "AVAILABLE",
  "primary": true
}
```

**Backward Compatibility Fields:**
```json
{
  "bitbucketProjectKey": "DCE",
  "repositorySlug": "backend",
  "cloneUrl": "https://..."
}
```

### 2. Resilient Evidence Parsing
Updated `JenkinsEvidenceCollectorService` with fallback parsing:

1. **Try canonical parsing first** - Deserialize as `RepositoryResolutionResult`
2. **Fallback to legacy parsing** - Extract fields manually with multiple field name attempts:
   - `projectKey` OR `bitbucketProjectKey`
   - `primaryRepositorySlug` OR `repositorySlug` OR `slug` OR `repoSlug` OR `primaryRepository`

3. **Validation:**
   - Fail if `primaryRepositorySlug` is missing/blank
   - Fail if `projectKey` is missing/blank

### 3. Diagnostic Logging (NO CREDENTIALS)

**RepositoryResolutionEvidenceService:**
```
REPOSITORY_RESOLUTION_EVIDENCE_SAVED: 
  caseId=..., 
  evidenceId=..., 
  targetKey=backend, 
  projectKey=DCE, 
  repositorySlug=backend, 
  sourceBranch=test2, 
  confidence=1.0, 
  primaryResolved=true
```

**JenkinsEvidenceCollectorService:**
```
JENKINS_REPOSITORY_RESOLVED: 
  caseId=..., 
  projectKey=DCE, 
  repositorySlug=backend, 
  primaryResolved=true
```

### 4. Enhanced Golden Path Response

**Step 3 (Repository Resolution):**
```json
"3_repository_resolution": {
  "result": "SUCCESS",
  "evidenceId": "uuid",
  "confidence": 1.0,
  "targetKey": "backend",
  "projectKey": "DCE",
  "repositorySlug": "backend",
  "primaryRepositorySlug": "backend",
  "sourceBranch": "test2",
  "repositoryState": "AVAILABLE",
  "synthetic": false
}
```

### 5. Status Calculation

**Overall Status Logic:**
- **SUCCESS**: All steps succeeded
- **PARTIAL_SUCCESS**: Mandatory steps succeeded, but optional steps failed/skipped
- **FAILED**: Any mandatory step failed

**Mandatory Steps:**
- `0_target_validation`
- `1_case_resolution`
- `2_jira_evidence`
- `3_repository_resolution`

**Optional Steps:**
- `4_jenkins_evidence` (skipped if repo resolution fails)
- `5_loki_evidence`
- `6_tempo_evidence`
- `7_deterministic_rca`

## Files Modified

### 1. RepositoryResolutionEvidenceService.java
- **buildSanitizedEvidence()**: Added canonical fields matching `RepositoryResolutionResult`
- **collectRepositoryResolution()**: Enhanced diagnostic logging

### 2. JenkinsEvidenceCollectorService.java
- **readRepositoryResolution()**: Try canonical parse, fallback to legacy
- **parseLegacyEvidence()**: Extract fields with multiple field name attempts
- **extractField()**: Helper for backward-compatible field extraction
- **collect()**: Added diagnostic logging and projectKey validation

### 3. GoldenPathOrchestrationService.java
- **collectRepositoryResolution()**: Extract and include evidence fields in response
- **calculateOverallStatus()**: Determine SUCCESS/PARTIAL_SUCCESS/FAILED
- **executeGoldenPath()**: Use calculated status instead of hardcoded

## Testing

### Test Case 1: Canonical Evidence Works
```powershell
# Execute Golden Path
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

# Check repository resolution
$repoStep = $response.steps.'3_repository_resolution'
Write-Host "Result: $($repoStep.result)"
Write-Host "Project Key: $($repoStep.projectKey)"
Write-Host "Repository Slug: $($repoStep.repositorySlug)"
Write-Host "Primary Slug: $($repoStep.primaryRepositorySlug)"
Write-Host "Source Branch: $($repoStep.sourceBranch)"

# Check Jenkins (should succeed now)
$jenkinsStep = $response.steps.'4_jenkins_evidence'
Write-Host "Jenkins Result: $($jenkinsStep.result)"

# Check overall status
Write-Host "Overall Status: $($response.status)"
```

### Test Case 2: Verify Evidence Structure
```sql
-- Check repository resolution evidence
SELECT 
    id,
    confidence,
    content_text::json->>'projectKey' AS project_key,
    content_text::json->>'primaryRepositorySlug' AS primary_slug,
    content_text::json->>'repositorySlug' AS repo_slug,
    content_text::json->>'bitbucketProjectKey' AS bitbucket_project,
    content_text::json->>'sourceBranch' AS source_branch,
    content_text::json->>'repositoryState' AS state
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected result:
-- project_key | primary_slug | repo_slug | bitbucket_project | source_branch | state
-- DCE         | backend      | backend   | DCE               | test2         | AVAILABLE
```

### Test Case 3: No Secret Leak
```sql
-- Verify no credentials in evidence
SELECT id, content_text
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION'
  AND (
    content_text LIKE '%password%' OR
    content_text LIKE '%token%' OR
    content_text LIKE '%secret%' OR
    content_text LIKE '%credential%' OR
    content_text ~ '://[^@]+:[^@]+@' -- user:pass pattern
  );
-- Should return 0 rows
```

### Test Case 4: Jenkins Consumes Evidence
```sql
-- Check that Jenkins evidence was created
SELECT 
    id,
    evidence_type,
    source,
    confidence,
    created_at
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type IN ('JENKINS_BUILD', 'JENKINS_BUILD_CONTEXT')
ORDER BY created_at;
```

### Test Case 5: PARTIAL_SUCCESS Status
```powershell
# Simulate Jenkins failure (disconnect Jenkins)
# Repository resolution should succeed
# Jenkins should be SKIPPED
# Overall status should be PARTIAL_SUCCESS

$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

Write-Host "Repository: $($response.steps.'3_repository_resolution'.result)"
Write-Host "Jenkins: $($response.steps.'4_jenkins_evidence'.result)"
Write-Host "Overall: $($response.status)"
# Expected: Repository=SUCCESS, Jenkins=SKIPPED or FAILED, Overall=PARTIAL_SUCCESS
```

## Expected Console Logs

```
GOLDEN_PATH_START: jiraKey=FIZZMS-8346, targetKey=backend, forceNew=true
TARGET_VALIDATED: targetKey=backend
CASE_CREATED: jiraKey=FIZZMS-8346, targetKey=backend, caseId=...
BITBUCKET_REPOSITORIES_FETCHED: count=25
REPOSITORY_RESOLVED_EXACT: targetKey=backend, slug=backend
REPOSITORY_RESOLUTION_EVIDENCE_SAVED: caseId=..., evidenceId=..., targetKey=backend, projectKey=DCE, repositorySlug=backend, sourceBranch=test2, confidence=1.0, primaryResolved=true
REPOSITORY_RESOLUTION_SUCCESS: caseId=..., evidenceId=..., targetKey=backend, projectKey=DCE, repositorySlug=backend
JENKINS_REPOSITORY_RESOLVED: caseId=..., projectKey=DCE, repositorySlug=backend, primaryResolved=true
GOLDEN_PATH_COMPLETE: jiraKey=FIZZMS-8346, status=SUCCESS, caseId=...
```

## Success Criteria

✅ **Repository Resolution Evidence**
- Contains `projectKey` (canonical)
- Contains `primaryRepositorySlug` (canonical)
- Contains `repositorySlug` (backward compatibility)
- Contains `bitbucketProjectKey` (backward compatibility)
- Contains `sourceBranch=test2`
- Contains `repositoryState=AVAILABLE`
- No credentials in evidence

✅ **Jenkins Integration**
- Reads repository resolution successfully
- Extracts `projectKey=DCE`
- Extracts `primaryRepositorySlug=backend`
- Does not fail with "Primary repository was not resolved"
- Creates Jenkins evidence

✅ **Golden Path Status**
- SUCCESS when all steps succeed
- PARTIAL_SUCCESS when Jenkins fails but repo resolution succeeds
- FAILED when repo resolution fails
- Detailed step-level results

✅ **Diagnostic Logging**
- Logs caseId, targetKey, projectKey, repositorySlug, sourceBranch
- Logs primaryResolved=true/false
- Does NOT log credentials or tokens

✅ **Backward Compatibility**
- Legacy evidence with only `repositorySlug` is parsed
- Legacy evidence with `bitbucketProjectKey` is parsed
- Fallback field name extraction works

## Build & Run

```powershell
# In IntelliJ: Build → Rebuild Project
# Or use Maven (if available):
mvn clean compile -DskipTests
mvn test

# Run application
# In IntelliJ: Run → Run 'ReplayFixApplication'

# Execute Golden Path
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

$response | ConvertTo-Json -Depth 10
```

## Summary

**Problem:** Jenkins could not consume repository resolution evidence due to field name mismatch.

**Solution:** 
1. Producer writes canonical + backward-compatible fields
2. Consumer tries canonical parse, falls back to legacy parsing
3. Enhanced validation and diagnostic logging
4. PARTIAL_SUCCESS status for graceful degradation

**Result:** 
- Repository resolution evidence is now consumable by Jenkins
- Backward compatibility maintained
- Clear diagnostic logging without credentials
- Proper status reporting (SUCCESS/PARTIAL_SUCCESS/FAILED)

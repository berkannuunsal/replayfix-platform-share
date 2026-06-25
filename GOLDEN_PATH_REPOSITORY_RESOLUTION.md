# Golden Path - Repository Resolution Integration

## Problem Solved
Golden Path Jenkins step was failing with "Repository resolution evidence not found" error because repository resolution was not being executed before Jenkins evidence collection.

## Solution Implemented

### 1. New Service: RepositoryResolutionEvidenceService
Created dedicated service for collecting repository resolution evidence:
- Uses existing `BitbucketClient` to fetch repository list
- Uses existing `RepositoryResolverService` for resolution logic
- Validates `targetKey` against `ReplayLabProperties` configuration
- **Exact match priority**: If `targetKey` matches configured repository slug → 100% confidence
- **Fallback to resolver**: If no exact match, uses resolver service with Jira signals
- **Sanitized output**: Removes credentials from clone URLs
- Stores as `REPOSITORY_RESOLUTION` evidence

**Key Features:**
- ✅ No duplicate Bitbucket client
- ✅ No credential leak (URLs sanitized)
- ✅ Source branch from target config (defaults to `test2`)
- ✅ Explicit targetKey preserved (no fallback to different target)
- ✅ Confidence scoring based on match type

### 2. Updated Golden Path Flow

**New Step Order:**
1. **0_target_validation** - Validate target exists in config
2. **1_case_resolution** - Find or create case
3. **2_jira_evidence** - Collect Jira evidence
4. **3_repository_resolution** ⭐ NEW - Resolve repository
5. **4_jenkins_evidence** - Collect Jenkins (only if repo resolved)
6. **5_loki_evidence** - Collect Loki logs
7. **6_tempo_evidence** - Collect Tempo traces
8. **7_deterministic_rca** - Generate RCA
9. **8_summary** - Build summary

### 3. Conditional Jenkins Execution

```java
// Step 4: Collect Jenkins evidence (only if repository resolved)
if (repositoryResolved) {
    steps.put("4_jenkins_evidence", collectJenkinsEvidence(caseId));
} else {
    Map<String, Object> jenkinsSkipped = new LinkedHashMap<>();
    jenkinsSkipped.put("result", "SKIPPED");
    jenkinsSkipped.put("reason", "Repository resolution failed: " + repoResolution.get("error"));
    steps.put("4_jenkins_evidence", jenkinsSkipped);
}
```

**Behavior:**
- Repository resolution SUCCESS → Jenkins executes
- Repository resolution FAILED → Jenkins SKIPPED with reason

### 4. Evidence Structure

**REPOSITORY_RESOLUTION Evidence:**
```json
{
  "applicationKey": "backend",
  "targetKey": "backend",
  "bitbucketProjectKey": "BSS",
  "repositorySlug": "bss-backend",
  "repositoryName": "BSS Backend",
  "cloneUrl": "https://bitbucket.etiya.com/scm/bss/bss-backend.git",
  "sourceBranch": "test2",
  "confidence": 1.0,
  "matchedSignals": ["EXACT_TARGET_CONFIG_MATCH"],
  "resolutionMethod": "TARGET_CONFIG_EXACT_MATCH",
  "repositoryState": "AVAILABLE"
}
```

**Security:**
- ✅ No passwords in evidence
- ✅ No tokens in evidence
- ✅ No credentials in evidence
- ✅ Clone URLs sanitized (removes `user:pass@` patterns)

### 5. Response Example

**Success Case:**
```json
{
  "jiraKey": "FIZZMS-8346",
  "requestedTargetKey": "backend",
  "resolvedTargetKey": "backend",
  "caseId": "4a706e42-a3fd-4477-b5a9-2328bfad2096",
  "reusedExistingCase": false,
  "forceNew": false,
  "status": "SUCCESS",
  "synthetic": false,
  "steps": {
    "0_target_validation": {
      "result": "SUCCESS",
      "targetKey": "backend",
      "targetExists": true
    },
    "3_repository_resolution": {
      "result": "SUCCESS",
      "evidenceId": "evidence-uuid",
      "confidence": 1.0,
      "targetKey": "backend",
      "synthetic": false
    },
    "4_jenkins_evidence": {
      "result": "SUCCESS",
      "count": 1
    }
  }
}
```

**Failed Repository Resolution:**
```json
{
  "steps": {
    "3_repository_resolution": {
      "result": "FAILED",
      "error": "Target not configured: invalid-target",
      "targetKey": "invalid-target"
    },
    "4_jenkins_evidence": {
      "result": "SKIPPED",
      "reason": "Repository resolution failed: Target not configured: invalid-target"
    }
  }
}
```

## Configuration Required

### replay-targets.yml
```yaml
targets:
  backend:
    repository: bss-backend  # Exact match slug
    clone-url: ${BACKEND_CLONE_URL}
    build-command: mvn -B clean test
    git:
      source-branch: test2  # Used in evidence
      branch-prefix: replaylab/
```

## Testing

### Test Case 1: Successful Resolution
```powershell
# Execute Golden Path with backend target
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

# Check repository resolution
$repoStep = $response.steps.'3_repository_resolution'
Write-Host "Repository Resolution: $($repoStep.result)"
Write-Host "Confidence: $($repoStep.confidence)"
Write-Host "Target: $($repoStep.targetKey)"

# Check Jenkins (should run)
$jenkinsStep = $response.steps.'4_jenkins_evidence'
Write-Host "Jenkins: $($jenkinsStep.result)"
```

### Test Case 2: Failed Resolution
```powershell
# Execute with invalid target
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=invalid-target" `
    -Method Post

# Check Jenkins (should be SKIPPED)
$jenkinsStep = $response.steps.'4_jenkins_evidence'
Write-Host "Jenkins Result: $($jenkinsStep.result)"
Write-Host "Skip Reason: $($jenkinsStep.reason)"
```

### Test Case 3: Evidence Verification
```sql
-- Check repository resolution evidence
SELECT 
    e.evidence_type,
    e.confidence,
    e.content_text::json->>'targetKey' AS target_key,
    e.content_text::json->>'repositorySlug' AS repo_slug,
    e.content_text::json->>'sourceBranch' AS source_branch,
    e.content_text::json->>'resolutionMethod' AS method
FROM rf_evidence e
WHERE e.case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
  AND e.evidence_type = 'REPOSITORY_RESOLUTION';
```

### Test Case 4: No Credential Leak
```sql
-- Verify no secrets in evidence
SELECT id, content_text
FROM rf_evidence
WHERE case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
  AND evidence_type = 'REPOSITORY_RESOLUTION'
  AND (
    content_text LIKE '%password%' OR
    content_text LIKE '%token%' OR
    content_text LIKE '%secret%' OR
    content_text LIKE '%@%:%'
  );
-- Should return 0 rows
```

## Manual Re-execution on Existing Case

To manually re-run repository resolution on existing case:

```powershell
# Option 1: Force new case
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

# Option 2: Use different targetKey to create new case
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend" `
    -Method Post
```

## Files Modified

1. **RepositoryResolutionEvidenceService.java** ⭐ NEW
   - Main service for repository resolution evidence collection
   - Uses existing BitbucketClient and RepositoryResolverService
   - Sanitizes URLs, preserves targetKey, source branch from config

2. **GoldenPathOrchestrationService.java** 🔧 UPDATED
   - Added `RepositoryResolutionEvidenceService` dependency
   - Added `collectRepositoryResolution()` method
   - Updated step order (3_repository_resolution before Jenkins)
   - Conditional Jenkins execution based on repo resolution
   - Updated step numbers (Jenkins 3→4, Loki 4→5, etc.)

3. **replay-targets.yml** 🔧 UPDATED
   - Added `backend` target configuration

## Verification Checklist

- [ ] Repository resolution runs before Jenkins
- [ ] REPOSITORY_RESOLUTION evidence persisted to database
- [ ] Resolution failure → Jenkins SKIPPED
- [ ] Resolution success → Jenkins runs
- [ ] targetKey=backend preserved (no fallback)
- [ ] sourceBranch=test2 in evidence
- [ ] synthetic=false
- [ ] No credentials in evidence content
- [ ] Confidence scoring works (1.0 for exact match)
- [ ] Step numbers correct (3_repository_resolution, 4_jenkins, etc.)

## Build & Test Commands

```powershell
# In IntelliJ: Build → Rebuild Project
# Or use Maven wrapper if available:
.\mvnw.cmd clean compile -DskipTests
.\mvnw.cmd test -Dtest=RepositoryResolutionEvidenceServiceTest

# Run application
.\mvnw.cmd spring-boot:run

# Test Golden Path
$response = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend" -Method Post
$response | ConvertTo-Json -Depth 10
```

## Expected Output

**Console Log:**
```
GOLDEN_PATH_START: jiraKey=FIZZMS-8346, targetKey=backend, forceNew=false
TARGET_VALIDATED: targetKey=backend
CASE_CREATED: jiraKey=FIZZMS-8346, targetKey=backend, caseId=..., forceNew=false
BITBUCKET_REPOSITORIES_FETCHED: count=25
REPOSITORY_RESOLVED_EXACT: targetKey=backend, slug=bss-backend
REPOSITORY_RESOLUTION_EVIDENCE_SAVED: caseId=..., evidenceId=..., confidence=1.0
REPOSITORY_RESOLUTION_SUCCESS: caseId=..., evidenceId=..., targetKey=backend
GOLDEN_PATH_COMPLETE: jiraKey=FIZZMS-8346, status=SUCCESS, caseId=...
```

**Database Evidence:**
```sql
evidence_type          | confidence | target_key | repository_slug | source_branch
-----------------------|------------|------------|-----------------|---------------
REPOSITORY_RESOLUTION  | 1.0        | backend    | bss-backend     | test2
```

## Success Criteria

✅ Repository resolution executes before Jenkins
✅ Evidence type = REPOSITORY_RESOLUTION
✅ Evidence persisted with confidence score
✅ Target key preserved (backend)
✅ Source branch = test2
✅ No secrets in evidence
✅ Jenkins skipped if resolution fails
✅ Jenkins runs if resolution succeeds
✅ Explicit target (no fallback)
✅ All step numbers correct

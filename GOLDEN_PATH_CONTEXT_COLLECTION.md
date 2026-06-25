# Golden Path - Context Collection Integration

## Problem Solved
After successful Jenkins evidence collection, the Golden Path was not collecting Loki logs, Tempo traces, or creating the AI input bundle, causing:
- **5_loki_evidence**: UNAVAILABLE
- **6_tempo_evidence**: UNAVAILABLE  
- **7_deterministic_rca**: FAILED - "Required evidence not found: AI_INPUT_BUNDLE"

## Solution

### Integrated Existing Context Orchestration

The Golden Path now calls the existing `ReplayOrchestrator.collectContext(caseId)` after Jenkins evidence collection, reusing all existing services:

**No new clients created:**
- ✅ Reuses existing `LokiClient`
- ✅ Reuses existing `TempoClient`
- ✅ Reuses existing `BitbucketClient` (for source context)
- ✅ Reuses existing `AiInputBundleRefreshService`
- ✅ Reuses existing `DeterministicRootCauseRefreshService`

### Updated Step Flow

**New Golden Path Steps:**
1. **0_target_validation** - Validate target configuration
2. **1_case_resolution** - Find or create case
3. **2_jira_evidence** - Collect Jira issue
4. **3_repository_resolution** - Resolve repository (projectKey, slug, branch)
5. **4_jenkins_evidence** - Collect Jenkins builds
6. **5_context_collection** ⭐ NEW - Call ReplayOrchestrator.collectContext()
7. **6_loki_evidence** ⭐ NEW - Report Loki logs from evidence
8. **7_tempo_evidence** ⭐ NEW - Report Tempo traces (optional)
9. **8_ai_input_bundle** ⭐ NEW - Create jenkins-validated-ai-bundle
10. **9_deterministic_rca** - Generate deterministic RCA
11. **10_summary** - Build summary

### Context Collection (Step 5)

```java
private Map<String, Object> collectContextEvidence(UUID caseId) {
    // Call existing orchestrator on the SAME case
    replayOrchestrator.collectContext(caseId);
    
    // Reports:
    // - lokiEvidenceCount
    // - tempoEvidenceCount  
    // - sourceContextCount
}
```

**Evidence Created by collectContext():**
- `LOKI_QUERY_PLAN`
- `LOKI_LOG` (required)
- `LOKI_CORRELATION_SIGNALS`
- `LOKI_SECOND_PASS`
- `INCIDENT_TIMELINE`
- `TEMPO_ENRICHMENT` (optional)
- `CONFLUENCE_PAGE` (knowledge)
- `SOURCE_CONTEXT` (from Bitbucket + Jenkins)

### AI Input Bundle (Step 8)

```java
private Map<String, Object> collectAiInputBundle(UUID caseId) {
    // Create jenkins-validated-ai-bundle
    // Does NOT call external AI provider
    aiInputBundleRefreshService.refresh(caseId);
    
    // Check if bundle was created
    // source = "jenkins-validated-ai-bundle"
}
```

**Key Points:**
- ✅ Created when `REPLAYLAB_AI_ENABLED=false`
- ✅ No external LLM call
- ✅ Internal normalized evidence bundle
- ✅ Required for deterministic RCA

### Deterministic RCA (Step 9)

```java
private Map<String, Object> generateDeterministicRca(UUID caseId) {
    // Check if AI_INPUT_BUNDLE exists
    if (bundle not found) {
        return SKIPPED with reason "AI_INPUT_BUNDLE_NOT_AVAILABLE";
    }
    
    // Run deterministic RCA service
    deterministicRcaService.refresh(caseId);
}
```

**Behavior:**
- ✅ Skipped if AI_INPUT_BUNDLE missing
- ✅ No unhandled failure when evidence absent
- ✅ Runs only when bundle exists

### Status Calculation

**Mandatory Steps (must succeed):**
- `0_target_validation`
- `1_case_resolution`
- `2_jira_evidence`
- `3_repository_resolution`
- `4_jenkins_evidence`
- `5_context_collection`
- `6_loki_evidence` (required for real incident path)

**Optional Steps (can be SKIPPED/UNAVAILABLE):**
- `7_tempo_evidence` (traces optional)
- `8_ai_input_bundle` (can fail if dependencies missing)
- `9_deterministic_rca` (can be skipped if bundle unavailable)

**Status Logic:**
- **SUCCESS**: All mandatory steps succeeded
- **PARTIAL_SUCCESS**: Mandatory succeeded, optional failed/skipped
- **FAILED**: Any mandatory step failed

### Loki Evidence Handling

**Required for Real Incident Path:**
```java
private Map<String, Object> reportLokiEvidence(UUID caseId) {
    List<EvidenceEntity> lokiEvidence = ...
        .filter(e -> e.getEvidenceType() == LOKI_LOG || 
                     e.getEvidenceType() == LOKI_LOGS)
        .toList();
    
    if (lokiEvidence.isEmpty()) {
        return UNAVAILABLE;  // Real failure stored
    } else {
        return SUCCESS with count and evidenceIds;
    }
}
```

**No Synthetic Evidence:**
- ✅ Real Loki failure reason stored
- ✅ No fabricated evidence created
- ✅ Status reflects actual collection result

### Tempo Evidence Handling

**Optional - Does Not Block RCA:**
```java
private Map<String, Object> reportTempoEvidence(UUID caseId) {
    List<EvidenceEntity> tempoEvidence = ...
        .filter(e -> e.getEvidenceType() == TEMPO_TRACE || 
                     e.getEvidenceType() == TEMPO_ENRICHMENT)
        .toList();
    
    if (tempoEvidence.isEmpty()) {
        return UNAVAILABLE with reason "No Tempo trace available (optional)";
    } else {
        return SUCCESS with count and evidenceIds;
    }
}
```

**Behavior:**
- ✅ Absence does not cause FAILED status
- ✅ Golden Path continues without Tempo
- ✅ Logged as INFO (not error)

## Same Case Continuity

**Critical Requirements Met:**
- ✅ Same `caseId` used throughout
- ✅ `targetKey=backend` preserved
- ✅ `synthetic=false` maintained
- ✅ `projectKey=DCE` from repository resolution
- ✅ `repositorySlug=backend` preserved
- ✅ `sourceBranch=test2` maintained

**No New Case Created:**
```java
// Step 5 calls orchestrator on EXISTING case
replayOrchestrator.collectContext(caseId);  // Same caseId!
```

## Diagnostic Logging (No Secrets)

**Context Collection:**
```
CONTEXT_COLLECTION_START: caseId=...
CONTEXT_COLLECTION_SUCCESS: caseId=..., loki=3, tempo=1, sourceContext=1
```

**Loki Evidence:**
```
LOKI_EVIDENCE_FOUND: caseId=..., count=3
LOKI_EVIDENCE_UNAVAILABLE: caseId=... (when missing)
```

**Tempo Evidence:**
```
TEMPO_EVIDENCE_FOUND: caseId=..., count=1
TEMPO_EVIDENCE_UNAVAILABLE: caseId=... (optional) (when missing)
```

**AI Input Bundle:**
```
AI_INPUT_BUNDLE_START: caseId=...
AI_INPUT_BUNDLE_CREATED: caseId=..., evidenceId=...
AI_INPUT_BUNDLE_NOT_CREATED: caseId=... (error case)
```

**Deterministic RCA:**
```
DETERMINISTIC_RCA_START: caseId=...
DETERMINISTIC_RCA_SUCCESS: caseId=..., evidenceId=...
DETERMINISTIC_RCA_SKIPPED: AI_INPUT_BUNDLE not found for caseId=...
```

**What's NOT Logged:**
- ❌ Full log content
- ❌ Source code
- ❌ Tokens or credentials
- ❌ Clone URLs with credentials

## Files Modified

### 1. GoldenPathOrchestrationService.java ⭐ MAJOR UPDATE

**Dependencies Added:**
```java
private final ReplayOrchestrator replayOrchestrator;
private final AiInputBundleRefreshService aiInputBundleRefreshService;
```

**New Methods:**
- `collectContextEvidence(UUID caseId)` - Calls existing orchestrator
- `reportLokiEvidence(UUID caseId)` - Reports Loki evidence from DB
- `reportTempoEvidence(UUID caseId)` - Reports Tempo evidence (optional)
- `collectAiInputBundle(UUID caseId)` - Creates jenkins-validated-ai-bundle

**Updated Methods:**
- `executeGoldenPath()` - New step flow (5-10)
- `generateDeterministicRca()` - Check bundle before execution
- `calculateOverallStatus()` - New mandatory/optional step classification

### 2. Existing Services (Reused, Not Modified)

- `ReplayOrchestrator` - collectContext() method
- `AiInputBundleRefreshService` - refresh() method
- `DeterministicRootCauseRefreshService` - refresh() method
- `AdaptiveLokiSearchService` - Loki search
- `TempoEnrichmentService` - Tempo traces
- `SourceContextCollectorService` - Source code context

## Testing

### Test Case 1: Full Success Chain
```powershell
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

# Check all steps
Write-Host "Repository: $($response.steps.'3_repository_resolution'.result)"
Write-Host "Jenkins: $($response.steps.'4_jenkins_evidence'.result)"
Write-Host "Context Collection: $($response.steps.'5_context_collection'.result)"
Write-Host "Loki: $($response.steps.'6_loki_evidence'.result)"
Write-Host "Tempo: $($response.steps.'7_tempo_evidence'.result)"
Write-Host "AI Bundle: $($response.steps.'8_ai_input_bundle'.result)"
Write-Host "RCA: $($response.steps.'9_deterministic_rca'.result)"
Write-Host "Overall: $($response.status)"

# Expected: All SUCCESS, Overall = SUCCESS
```

### Test Case 2: AI Bundle Created Without AI Provider
```sql
-- Check AI_INPUT_BUNDLE exists even with REPLAYLAB_AI_ENABLED=false
SELECT 
    id,
    evidence_type,
    source,
    confidence,
    created_at
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'AI_INPUT_BUNDLE'
  AND source = 'jenkins-validated-ai-bundle';

-- Expected: 1 row returned
```

### Test Case 3: Tempo Optional (Missing)
```powershell
# If Tempo is unavailable
$response = Invoke-RestMethod ...

Write-Host "Tempo: $($response.steps.'7_tempo_evidence'.result)"
Write-Host "Overall: $($response.status)"

# Expected: Tempo = UNAVAILABLE, Overall = SUCCESS or PARTIAL_SUCCESS (not FAILED)
```

### Test Case 4: RCA Skipped When Bundle Missing
```powershell
# Simulate bundle creation failure
# (e.g., missing source context)

$response = Invoke-RestMethod ...

Write-Host "AI Bundle: $($response.steps.'8_ai_input_bundle'.result)"
Write-Host "RCA: $($response.steps.'9_deterministic_rca'.result)"
Write-Host "RCA Reason: $($response.steps.'9_deterministic_rca'.reason)"

# Expected: Bundle = FAILED, RCA = SKIPPED, reason = "AI_INPUT_BUNDLE_NOT_AVAILABLE"
```

### Test Case 5: Same Case Continuity
```sql
-- Verify all evidence uses the same caseId
SELECT 
    case_id,
    evidence_type,
    source,
    created_at
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
ORDER BY created_at;

-- Expected: All evidence types for single caseId:
-- JIRA_ISSUE
-- REPOSITORY_RESOLUTION
-- JENKINS_BUILD
-- LOKI_LOG
-- TEMPO_ENRICHMENT (optional)
-- SOURCE_CONTEXT
-- AI_INPUT_BUNDLE
-- ROOT_CAUSE_ANALYSIS
```

### Test Case 6: No Synthetic Evidence
```sql
-- Verify all evidence is real
SELECT 
    case_id,
    evidence_type,
    source,
    CASE 
        WHEN source LIKE '%synthetic%' OR source LIKE '%mock%' 
        THEN 'SYNTHETIC'
        ELSE 'REAL'
    END AS evidence_source_type
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e';

-- Expected: All evidence_source_type = 'REAL'
```

### Test Case 7: Loki Failure Produces PARTIAL_SUCCESS
```powershell
# Disconnect Loki or simulate failure

$response = Invoke-RestMethod ...

Write-Host "Loki: $($response.steps.'6_loki_evidence'.result)"
Write-Host "Overall: $($response.status)"

# Expected: Loki = UNAVAILABLE or FAILED, Overall = PARTIAL_SUCCESS (not SUCCESS)
```

## Expected Console Logs

```
GOLDEN_PATH_START: jiraKey=FIZZMS-8346, targetKey=backend, forceNew=true
TARGET_VALIDATED: targetKey=backend
CASE_CREATED: jiraKey=FIZZMS-8346, targetKey=backend, caseId=...
REPOSITORY_RESOLUTION_EVIDENCE_SAVED: caseId=..., targetKey=backend, projectKey=DCE, repositorySlug=backend, sourceBranch=test2, confidence=1.0, primaryResolved=true
JENKINS_REPOSITORY_RESOLVED: caseId=..., projectKey=DCE, repositorySlug=backend, primaryResolved=true
CONTEXT_COLLECTION_START: caseId=...
CONTEXT_COLLECTION_SUCCESS: caseId=..., loki=3, tempo=1, sourceContext=1
LOKI_EVIDENCE_FOUND: caseId=..., count=3
TEMPO_EVIDENCE_FOUND: caseId=..., count=1
AI_INPUT_BUNDLE_START: caseId=...
AI_INPUT_BUNDLE_CREATED: caseId=..., evidenceId=...
DETERMINISTIC_RCA_START: caseId=...
DETERMINISTIC_RCA_SUCCESS: caseId=..., evidenceId=...
GOLDEN_PATH_COMPLETE: jiraKey=FIZZMS-8346, status=SUCCESS, caseId=...
```

## Success Criteria

✅ **Context Collection Integration**
- Calls existing `ReplayOrchestrator.collectContext()`
- No new Loki/Tempo clients created
- Same case used throughout

✅ **Evidence Chain**
- Jira → Repository → Jenkins → Loki/Tempo → AI Bundle → RCA
- All evidence for single caseId
- No synthetic evidence

✅ **AI Input Bundle**
- Created with `REPLAYLAB_AI_ENABLED=false`
- No external LLM call
- Source = `jenkins-validated-ai-bundle`

✅ **Deterministic RCA**
- Executes when AI_INPUT_BUNDLE exists
- Skipped when bundle unavailable
- No unhandled failures

✅ **Status Handling**
- SUCCESS when all mandatory succeed
- PARTIAL_SUCCESS when optional fail
- FAILED when mandatory fail

✅ **Loki Required**
- Mandatory for real incident path
- Real failure reason stored
- No fabricated evidence

✅ **Tempo Optional**
- Absence does not block RCA
- UNAVAILABLE acceptable
- Golden Path continues

✅ **Logging**
- caseId, targetKey, evidence counts logged
- No credentials or full logs
- Safe diagnostic information

## Build & Run

```powershell
# In IntelliJ: Build → Rebuild Project

# Run application
# In IntelliJ: Run → Run 'ReplayLabApplication'

# Execute Golden Path
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=backend&forceNew=true" `
    -Method Post

$response | ConvertTo-Json -Depth 10
```

## Summary

**Problem:** Loki, Tempo, and AI bundle were not collected after Jenkins evidence.

**Solution:**
1. Integrated existing `ReplayOrchestrator.collectContext()` after Jenkins
2. Created AI input bundle using existing service
3. Made deterministic RCA conditional on bundle existence
4. Classified steps as mandatory vs optional for status calculation
5. Made Tempo optional, Loki required
6. Added safe diagnostic logging

**Result:**
- ✅ Complete evidence chain: Jira → Repo → Jenkins → Loki/Tempo → Bundle → RCA
- ✅ Same case continuity (`caseId`, `targetKey`, `synthetic=false`)
- ✅ No duplicate orchestration framework
- ✅ Proper status reporting (SUCCESS/PARTIAL_SUCCESS/FAILED)
- ✅ No synthetic evidence generated
- ✅ No secret leakage in logs

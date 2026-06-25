# Rovo RCA Integration

## Overview

Implements bidirectional integration between ReplayLab and Rovo Incident Commander:
1. **ReplayLab → Jira**: Publish Evidence Snapshot as Jira comment
2. **Rovo → Jira**: Rovo Incident Commander enriches snapshot with knowledge-based RCA
3. **Jira → ReplayLab**: Import Rovo RCA analysis back into ReplayLab

## Goal

Enable Rovo Incident Commander to:
- Read compact ReplayLab evidence snapshot from Jira issue
- Enrich it using Jira history and Confluence/EtiyaWiki knowledge
- Write structured RCA comment back to Jira
- ReplayLab imports and displays Rovo RCA on dashboard

## Architecture

```
┌─────────────┐                  ┌──────────┐                  ┌──────────────┐
│  ReplayLab  │ ──publish──────> │   Jira   │ <──reads──────── │ Rovo Incident│
│             │                  │  Issue   │                  │  Commander   │
│   Golden    │                  │          │                  │              │
│    Path     │                  │ Comment: │                  │  Enrichment  │
│             │                  │ Snapshot │                  │   w/ Wiki    │
│             │                  │          │                  │              │
│             │ <──import────────│ Comment: │ <──writes────────│              │
│  Dashboard  │                  │ Rovo RCA │                  │              │
└─────────────┘                  └──────────┘                  └──────────────┘
```

## Implementation

### Step 1: Evidence Snapshot Model

**File:** `ReplayLabEvidenceSnapshot.java`

```java
public record ReplayLabEvidenceSnapshot(
    String schemaVersion,        // "1.0"
    UUID caseId,
    String jiraKey,
    String targetKey,
    boolean synthetic,
    RepositoryInfo repository,
    JenkinsInfo jenkins,
    IncidentVersionInfo incidentVersion,
    RuntimeEvidenceInfo runtimeEvidence,
    SourceContextInfo sourceContext,
    DeterministicRcaInfo deterministicRca,
    Map<String, List<UUID>> evidenceIds,
    GuardrailsInfo guardrails
)
```

**Sub-Records:**

**RepositoryInfo:**
```java
record RepositoryInfo(
    String projectKey,
    String repositorySlug,
    String sourceBranch
)
```

**JenkinsInfo:**
```java
record JenkinsInfo(
    String jobName,
    Integer buildNumber,
    String commitSha
)
```

**IncidentVersionInfo:**
```java
record IncidentVersionInfo(
    String jenkinsCommitSha,
    String bitbucketCommitSha,  // if available
    String checkoutCommitSha,
    boolean exactMatch
)
```

**RuntimeEvidenceInfo:**
```java
record RuntimeEvidenceInfo(
    int lokiMatchedRowCount,
    int lokiFailedQueryCount,
    List<String> extractedTraceIds,
    List<String> extractedOrderIds,
    List<String> extractedCorrelationIds,
    int tempoRequestedTraceCount,
    int tempoFoundTraceCount
)
```

**SourceContextInfo:**
```java
record SourceContextInfo(
    int scannedFileCount,
    int matchedFileCount,
    List<MatchedFile> matchedFiles
) {
    record MatchedFile(
        String filePath,
        String snippet  // First 200 chars
    )
}
```

**DeterministicRcaInfo:**
```java
record DeterministicRcaInfo(
    String classification,
    String probableCause,
    double confidence,
    List<String> affectedApplications,
    List<String> supportingEvidence,
    List<String> missingEvidence,
    List<String> recommendedActions
)
```

**GuardrailsInfo:**
```java
record GuardrailsInfo(
    boolean evidenceOnly,              // true
    boolean noAutomaticMerge,          // true
    boolean noProductionDeployment,    // true
    boolean humanApprovalRequired      // true
)
```

### Step 2: Evidence Snapshot Service

**File:** `EvidenceSnapshotService.java`

**Key Features:**
- Builds snapshot from successful Golden Path case
- Extracts trace IDs, order IDs, correlation IDs from Loki logs
- Limits matched files to 5 with 200-char snippets
- **Does NOT include secrets, tokens, cookies, auth headers**

**Example Usage:**
```java
ReplayLabEvidenceSnapshot snapshot = snapshotService.buildSnapshot(caseId);
```

### Step 3: REST Endpoints

**File:** `RovoIntegrationController.java`

**GET /api/v1/cases/{caseId}/rovo/evidence-snapshot**

Returns full ReplayLab Evidence Snapshot JSON.

**Request:**
```powershell
$snapshot = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/ddf5caed-bad7-4eca-b791-5a47869121b0/rovo/evidence-snapshot" `
    -Method Get
```

**Response:**
```json
{
  "schemaVersion": "1.0",
  "caseId": "ddf5caed-bad7-4eca-b791-5a47869121b0",
  "jiraKey": "FIZZMS-10228",
  "targetKey": "backend",
  "synthetic": false,
  "repository": {
    "projectKey": "DCE",
    "repositorySlug": "backend",
    "sourceBranch": "test2"
  },
  "jenkins": {
    "jobName": "MODERNIZATION.BACKEND_BUILD_12",
    "buildNumber": 3066,
    "commitSha": "7b65a116baac31bcf5c8b294a41c00ebdab04cfc"
  },
  ...
}
```

**POST /api/v1/cases/{caseId}/rovo/publish-snapshot**

Publishes snapshot as Jira comment.

**Request:**
```powershell
$result = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/ddf5caed-bad7-4eca-b791-5a47869121b0/rovo/publish-snapshot" `
    -Method Post
```

**Response:**
```json
{
  "status": "SUCCESS",
  "caseId": "ddf5caed-bad7-4eca-b791-5a47869121b0",
  "jiraKey": "FIZZMS-10228",
  "snapshotVersion": "1.0",
  "commentLength": 2547
}
```

**POST /api/v1/cases/{caseId}/rovo/import-rovo-rca**

Imports Rovo RCA from Jira comment.

**Request:**
```powershell
$result = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/ddf5caed-bad7-4eca-b791-5a47869121b0/rovo/import-rovo-rca" `
    -Method Post
```

**Response:**
```json
{
  "status": "SUCCESS",
  "caseId": "ddf5caed-bad7-4eca-b791-5a47869121b0",
  "jiraKey": "FIZZMS-10228",
  "schemaVersion": "1.0",
  "confidence": 0.87,
  "probableRootCause": "Null pointer exception in RegionService"
}
```

### Step 4: Jira Comment Format

**File:** `RovoSnapshotPublisherService.java`

**Comment Structure:**

```
h3. ReplayLab Evidence Snapshot v1.0

*Case:* ddf5caed-bad7-4eca-b791-5a47869121b0
*Jira:* FIZZMS-10228
*Target:* backend
*Synthetic:* No

*Repository:* DCE/backend (test2)
*Jenkins Build:* MODERNIZATION.BACKEND_BUILD_12 #3066
*Commit:* {{7b65a116baac31bcf5c8b294a41c00ebdab04cfc}}

*Loki Logs:* 0 matched (2 queries failed)
*Source Context:* 0 files scanned, 0 matched

*Deterministic RCA:*
* Classification: UNCLASSIFIED
* Confidence: 0.31
* Probable Cause: Insufficient evidence

{panel:title=Guardrails|borderStyle=solid|borderColor=#ccc}
* Evidence-only analysis
* No automatic merge
* No production deployment
* Human approval required
{panel}

----
{noformat}
REPLAYLAB_EVIDENCE_SNAPSHOT_V1
{
  "schemaVersion": "1.0",
  "caseId": "ddf5caed-bad7-4eca-b791-5a47869121b0",
  ...full JSON...
}
REPLAYLAB_EVIDENCE_SNAPSHOT_END
{noformat}
```

**Key Features:**
- Human-readable summary first
- Machine-readable JSON block with clear markers
- Uses Jira markup (panels, noformat)
- Compact but complete

### Step 5: Rovo RCA Model

**File:** `RovoRcaAnalysis.java`

```java
public record RovoRcaAnalysis(
    String schemaVersion,
    String jiraKey,
    String probableRootCause,
    double confidence,
    List<String> facts,
    List<String> inferences,
    List<String> unknowns,
    List<RelatedIssue> relatedJiraIssues,
    List<ConfluenceReference> confluenceReferences,
    List<SuspectedCodeLocation> suspectedCodeLocations,
    String recommendedNextAction,
    String analysisTimestamp
) {
    record RelatedIssue(
        String key,
        String summary,
        String relationship
    )
    
    record ConfluenceReference(
        String title,
        String url,
        String relevance
    )
    
    record SuspectedCodeLocation(
        String filePath,
        String className,
        String methodName,
        String reason
    )
}
```

### Step 6: Rovo RCA Importer

**File:** `RovoRcaImporterService.java`

**Process:**
1. Fetch Jira issue
2. Search for block:
   ```
   REPLAYLAB_ROVO_RCA_V1
   { ...JSON... }
   REPLAYLAB_ROVO_RCA_END
   ```
3. Parse JSON as `RovoRcaAnalysis`
4. Persist as:
   - `evidenceType = ROVO_RCA`
   - `source = rovo-incident-commander`

**Pattern Matching:**
```java
Pattern RCA_BLOCK_PATTERN = Pattern.compile(
    "REPLAYLAB_ROVO_RCA_V1\\s*\\n(.+?)\\n" +
    "REPLAYLAB_ROVO_RCA_END",
    Pattern.DOTALL
);
```

### Step 7: Dashboard Integration

**TODO:** Dashboard DTO and UI to display:

**Rovo RCA Section:**
```
Rovo Incident Commander Analysis
==================================

Status: ✅ Available
Confidence: 0.87 (HIGH)

Probable Root Cause:
  Null pointer exception in RegionService.validateTimezone()

Facts (Evidence-based):
  • Jenkins build #3066 failed at 14:32
  • NullPointerException in RegionService line 142
  • No timezone validation before access

Inferences (Knowledge-based):
  • Similar issue occurred in FIZZMS-9845
  • Confluence doc mentions timezone validation pattern
  • RegionService recently refactored (FIZZMS-10102)

Unknowns:
  • Why timezone was null
  • Which client sent invalid request
  • Production impact scope

Related Jira Issues:
  • FIZZMS-9845: NPE in RegionService (resolved)
  • FIZZMS-10102: Refactor region validation

Confluence References:
  • "Timezone Validation Best Practices"
    https://wiki.etiya.com/...
    Relevance: Describes validation pattern

Suspected Code Locations:
  • RegionService.validateTimezone()
    Reason: NPE thrown at line 142
  • RegionController.updateRegion()
    Reason: Calls validateTimezone without null check

Recommended Next Action:
  Add null check before timezone validation in RegionService
  and update RegionController to validate input

[Show Raw JSON ▼]
```

## End-to-End Flow

### 1. Generate Evidence Snapshot

```powershell
# Golden Path completes successfully
$goldenPath = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-10228&targetKey=backend" `
    -Method Post

$caseId = $goldenPath.caseId

# Get snapshot
$snapshot = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/evidence-snapshot" `
    -Method Get

Write-Host "Snapshot version: $($snapshot.schemaVersion)"
Write-Host "Loki logs: $($snapshot.runtimeEvidence.lokiMatchedRowCount)"
```

### 2. Publish to Jira

```powershell
# Publish snapshot as Jira comment
$publish = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/publish-snapshot" `
    -Method Post

Write-Host "Published to Jira: $($publish.jiraKey)"
Write-Host "Comment length: $($publish.commentLength)"
```

### 3. Rovo Processes (External)

**Rovo Incident Commander:**
1. Monitors Jira for `REPLAYLAB_EVIDENCE_SNAPSHOT_V1` comments
2. Reads snapshot JSON
3. Enriches with:
   - Historical Jira issues
   - Confluence knowledge base
   - EtiyaWiki documentation
4. Generates RCA analysis
5. Posts comment with `REPLAYLAB_ROVO_RCA_V1` block

### 4. Import Rovo RCA

```powershell
# Import Rovo RCA from Jira
$import = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/import-rovo-rca" `
    -Method Post

Write-Host "Rovo RCA imported: $($import.status)"
Write-Host "Confidence: $($import.confidence)"
Write-Host "Root Cause: $($import.probableRootCause)"
```

### 5. View on Dashboard

```powershell
# Navigate to dashboard
Start-Process "http://localhost:8088/replaylab/?caseId=$caseId"
```

## Files Created/Modified

### New Files

1. ✅ **ReplayLabEvidenceSnapshot.java**
   - Evidence snapshot model with all sub-records
   - Schema version 1.0

2. ✅ **EvidenceSnapshotService.java**
   - Builds snapshot from Golden Path evidence
   - Extracts IDs from logs
   - Sanitizes sensitive data

3. ✅ **RovoIntegrationController.java**
   - GET /evidence-snapshot
   - POST /publish-snapshot
   - POST /import-rovo-rca

4. ✅ **RovoSnapshotPublisherService.java**
   - Formats Jira comment with markers
   - Human + machine readable

5. ✅ **RovoRcaAnalysis.java**
   - Rovo RCA model with enriched data
   - Related issues, Confluence refs

6. ✅ **RovoRcaImporterService.java**
   - Extracts RCA from Jira comments
   - Persists as ROVO_RCA evidence

### Modified Files

**None** - All new functionality in new files.

### Existing Files Used

- **EvidenceType.java**: `ROVO_RCA` already exists
- **JiraClient.java**: `addComment()` already exists
- **EvidenceService.java**: Used for persisting

## Security & Guardrails

**Does NOT Include:**
- ❌ Secrets, tokens, API keys
- ❌ Cookies, session IDs
- ❌ Authorization headers
- ❌ Raw sensitive payloads
- ❌ Customer PII

**Always Includes:**
- ✅ Evidence-only flag
- ✅ No automatic merge
- ✅ No production deployment
- ✅ Human approval required

## Testing

### Unit Tests

**Test: Snapshot Build**
```java
@Test
void testBuildSnapshot() {
    ReplayLabEvidenceSnapshot snapshot = snapshotService.buildSnapshot(caseId);
    
    assertEquals("1.0", snapshot.schemaVersion());
    assertEquals("FIZZMS-10228", snapshot.jiraKey());
    assertNotNull(snapshot.repository());
    assertNotNull(snapshot.jenkins());
    assertTrue(snapshot.guardrails().evidenceOnly());
}
```

**Test: No Secrets**
```java
@Test
void testSnapshotDoesNotContainSecrets() {
    ReplayLabEvidenceSnapshot snapshot = snapshotService.buildSnapshot(caseId);
    String json = objectMapper.writeValueAsString(snapshot);
    
    assertFalse(json.contains("password"));
    assertFalse(json.contains("token"));
    assertFalse(json.contains("Authorization"));
    assertFalse(json.contains("Cookie"));
}
```

**Test: Comment Format**
```java
@Test
void testJiraCommentFormat() {
    String comment = publisherService.buildJiraComment(snapshot);
    
    assertTrue(comment.contains("REPLAYLAB_EVIDENCE_SNAPSHOT_V1"));
    assertTrue(comment.contains("REPLAYLAB_EVIDENCE_SNAPSHOT_END"));
    assertTrue(comment.contains("h3. ReplayLab Evidence Snapshot"));
}
```

**Test: Rovo RCA Parsing**
```java
@Test
void testRovoRcaParsing() {
    String comment = "...\nREPLAYLAB_ROVO_RCA_V1\n{\"schemaVersion\":\"1.0\"...}\nREPLAYLAB_ROVO_RCA_END\n...";
    String json = importerService.extractRovoRcaFromComments(comment);
    
    assertNotNull(json);
    assertTrue(json.contains("schemaVersion"));
}
```

### Integration Tests

```powershell
# Test full roundtrip
$caseId = "ddf5caed-bad7-4eca-b791-5a47869121b0"

# 1. Get snapshot
$snapshot = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/evidence-snapshot" `
    -Method Get

# 2. Publish to Jira
$publish = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/publish-snapshot" `
    -Method Post

# 3. Manually add Rovo RCA comment to Jira (simulating Rovo)

# 4. Import Rovo RCA
$import = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/cases/$caseId/rovo/import-rovo-rca" `
    -Method Post

# 5. Verify ROVO_RCA evidence exists
```

### SQL Verification

```sql
-- Check ROVO_RCA evidence
SELECT 
    evidence_type,
    source,
    confidence,
    LEFT(content_text, 500) AS content_preview
FROM rf_evidence
WHERE case_id = 'ddf5caed-bad7-4eca-b791-5a47869121b0'
  AND evidence_type = 'ROVO_RCA'
ORDER BY created_at DESC;

-- Expected:
-- ROVO_RCA | rovo-incident-commander | 0.87 | {"schemaVersion":"1.0"...}
```

## Build and Test

```powershell
mvn clean compile -DskipTests
mvn test
```

**Expected:**
- ✅ All services compile
- ✅ Controllers registered
- ✅ Endpoints accessible
- ✅ Snapshot builds from Golden Path case
- ✅ Jira comment published
- ✅ Rovo RCA parsed and imported

## Next Steps (NOT IMPLEMENTED YET)

**Dashboard UI:**
- Add Rovo RCA section to case detail page
- Display facts, inferences, unknowns
- Show related Jira issues and Confluence links
- Expandable raw JSON view

**Scheduled Import:**
- Periodic polling for new Rovo RCA comments
- Webhook from Jira on comment added
- Auto-import when Rovo posts

**Enhanced Features (Future):**
- Generated fix suggestions
- Regression test generation
- Draft PR creation
- All require Rovo RCA as input

## Summary

**Implemented:**
- ✅ Evidence Snapshot model (schemaVersion 1.0)
- ✅ Evidence Snapshot service (builds from Golden Path)
- ✅ GET /evidence-snapshot endpoint
- ✅ POST /publish-snapshot endpoint (Jira comment)
- ✅ Rovo RCA model
- ✅ POST /import-rovo-rca endpoint
- ✅ RCA extraction from Jira comments
- ✅ ROVO_RCA evidence persistence
- ✅ Security (no secrets in snapshot)
- ✅ Guardrails (evidence-only, human approval)

**Not Implemented:**
- ❌ Dashboard UI for Rovo RCA display
- ❌ Generated fix
- ❌ Regression test generation
- ❌ Draft PR creation

**Result:**
Complete Rovo RCA roundtrip:
`ReplayLab Evidence Snapshot → Jira comment → Rovo RCA comment → ReplayLab ROVO_RCA evidence`

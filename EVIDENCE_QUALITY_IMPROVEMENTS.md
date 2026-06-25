# Evidence Quality Improvements

## Overview

Added comprehensive evidence quality diagnostics to the Golden Path without changing the core flow. The system now provides transparent assessment of evidence strength and demo readiness.

## Problem Statement

**Current Situation:**
```
status=SUCCESS
synthetic=false
jenkinsBuild=3066
commitSha=7b65a116baac31bcf5c8b294a41c00ebdab04cfc
incidentVersion=SUCCESS
aiInputBundle=SUCCESS
deterministicRca=SUCCESS
```

**However:**
```
classification=UNCLASSIFIED
confidence=0.31
Loki matched rows=0
Tempo found traces=0
SourceContext scannedFileCount=0
```

**Issue:** Technical SUCCESS but evidence quality is WEAK - system correctly doesn't invent evidence, but needs better diagnostics for demo readiness.

## Solution

### 1. Evidence Quality Model

**New Model: `EvidenceQuality`**

```java
public record EvidenceQuality(
    String jiraSignalQuality,
    int lokiMatchedRows,
    int lokiFailedQueryCount,
    int lokiTotalQueryCount,
    boolean correlationIdFound,
    boolean traceIdFound,
    boolean tempoTraceFound,
    int sourceScannedFileCount,
    int sourceMatchedFileCount,
    double rcaConfidence,
    String rcaClassification,
    String demoReadiness,
    String overallAssessment
) {}
```

**Fields:**
- **jiraSignalQuality**: Quality of extracted Jira signals
- **lokiMatchedRows**: Number of log lines matched
- **lokiFailedQueryCount**: Number of Loki queries that failed
- **lokiTotalQueryCount**: Total number of Loki queries attempted
- **correlationIdFound**: Whether correlation ID was found in logs
- **traceIdFound**: Whether trace ID was found in logs
- **tempoTraceFound**: Whether Tempo trace was successfully retrieved
- **sourceScannedFileCount**: Number of source files scanned
- **sourceMatchedFileCount**: Number of source files with matches
- **rcaConfidence**: Confidence score from RCA (0.0-1.0)
- **rcaClassification**: RCA classification (e.g., UNCLASSIFIED, DATA_ISSUE, etc.)
- **demoReadiness**: STRONG, MEDIUM, or WEAK
- **overallAssessment**: Human-readable summary of issues

### 2. Demo Readiness Calculation

**Algorithm:**

```java
private String calculateDemoReadiness(
    int lokiMatchedRows,
    boolean tempoTraceFound,
    int sourceMatchedFileCount,
    double rcaConfidence,
    String rcaClassification
) {
    boolean hasStrongLogs = lokiMatchedRows > 50;
    boolean hasTrace = tempoTraceFound;
    boolean hasSource = sourceMatchedFileCount > 0;
    boolean hasStrongRCA = rcaConfidence > 0.7 && !"UNCLASSIFIED".equals(rcaClassification);
    
    // STRONG: logs, trace/source, and strong RCA
    if (hasStrongLogs && (hasTrace || hasSource) && hasStrongRCA) {
        return "STRONG";
    }
    
    // MEDIUM: has some logs and valid incident version, but weak source/trace or medium RCA
    if (lokiMatchedRows > 10 && (hasSource || rcaConfidence > 0.3)) {
        return "MEDIUM";
    }
    
    // WEAK: RCA is only hypothesis or missing critical evidence
    return "WEAK";
}
```

**Criteria:**

**STRONG:**
- Loki matched > 50 logs
- Tempo trace OR source code matches
- RCA confidence > 0.7 with classification

**MEDIUM:**
- Loki matched > 10 logs
- Source matches OR RCA confidence > 0.3

**WEAK:**
- Everything else (hypothesis only, missing critical evidence)

### 3. Improved Source Context Extraction

**Problem:** Terms like "region", "tax_info", "billing account" don't match Java code.

**Solution:** Expand Jira-derived terms into code-like variants.

**Examples:**

| Jira Term | Expanded Variants |
|-----------|-------------------|
| `region` | `region`, `regionService`, `regionController`, `RegionService`, `RegionController`, `RegionRepository`, `RegionEntity` |
| `tax_info` | `tax_info`, `taxInfo`, `TaxInfo`, `tax-info`, `taxinfoservice`, `TaxInfoService`, `TaxInfoController` |
| `timezone` | `timezone`, `timeZone`, `TimeZone`, `timezoneservice`, `TimeZoneService` |
| `billing account` | `billing account`, `billingAccount`, `BillingAccount`, `billing_account`, `billing-account`, `account`, `billing`, `BillingAccountService`, `AccountBilling` |

**Implementation:**

```java
private List<String> expandTermVariants(String term) {
    List<String> variants = new ArrayList<>();
    
    // Original term
    variants.add(term);
    
    // Handle snake_case -> camelCase, PascalCase, kebab-case
    // Handle kebab-case -> camelCase, PascalCase, snake_case
    // Handle spaces -> camelCase, PascalCase, snake_case, kebab-case, individual words
    
    // Add common Java suffixes
    if (!term.contains("service") && !term.contains("controller")) {
        String base = term.replace("_", "").replace("-", "");
        variants.add(base + "service");
        variants.add(base + "controller");
        variants.add(base + "repository");
        variants.add(base + "entity");
        
        String pascalBase = capitalize(base);
        variants.add(pascalBase + "Service");
        variants.add(pascalBase + "Controller");
        variants.add(pascalBase + "Repository");
        variants.add(pascalBase + "Entity");
    }
    
    return variants;
}
```

**Scanned By:**
- File name matching
- Class name matching (via regex)
- Method name matching
- Endpoint annotation matching
- String constants
- SQL/query constants
- DTO field names

**Quality Indicators:**
- `scannedFileCount=0` → SOURCE_CONTEXT_EMPTY (not presented as successful)
- `matchedFileCount=0` → Warning included in evidence quality

### 4. Loki Diagnostics

**Current Issue:** Loki evidence stored but no per-query diagnostics.

**Solution:** Leverage existing `AdaptiveLokiSearchResult` structure.

**Already Captured:**
```java
public record LokiSearchAttempt(
    int priority,
    String reason,
    String logQl,
    int matchedCount,
    String error
)
```

**Status Per Query:**
- **SUCCESS_WITH_MATCHES**: `error == null && matchedCount > 0`
- **SUCCESS_NO_MATCHES**: `error == null && matchedCount == 0`
- **FAILED**: `error != null`

**Quality Assessment:**
```java
// Assess Loki log quality
allEvidence.stream()
    .filter(e -> e.getEvidenceType() == EvidenceType.LOKI_LOG)
    .findFirst()
    .ifPresent(loki -> {
        AdaptiveLokiSearchResult lokiResult = 
            objectMapper.readValue(loki.getContentText(), AdaptiveLokiSearchResult.class);
        lokiMatchedRows = lokiResult.logs() != null ? lokiResult.logs().size() : 0;
        lokiTotalQueryCount = lokiResult.attempts() != null ? lokiResult.attempts().size() : 0;
        lokiFailedQueryCount = (int) lokiResult.attempts().stream()
            .filter(attempt -> attempt.error() != null)
            .count();
        
        // Check for correlation/trace IDs in logs
        for (var log : lokiResult.logs()) {
            if (log.line().contains("correlationId")) {
                correlationIdFound = true;
            }
            if (log.line().contains("traceId")) {
                traceIdFound = true;
            }
        }
    });
```

**Do NOT count as strong evidence when:**
- `lokiMatchedRows=0` → Weak evidence
- `lokiFailedQueryCount > 0` → Degraded evidence quality
- No correlation or trace IDs → Cannot correlate with other systems

### 5. Overall Assessment

**Algorithm:**

```java
private String buildOverallAssessment(
    int lokiMatchedRows,
    int lokiFailedQueryCount,
    int sourceScannedFileCount,
    int sourceMatchedFileCount,
    double rcaConfidence
) {
    List<String> issues = new ArrayList<>();
    
    if (lokiMatchedRows == 0) {
        issues.add("No Loki logs matched");
    } else if (lokiMatchedRows < 10) {
        issues.add("Very few logs matched (" + lokiMatchedRows + ")");
    }
    
    if (lokiFailedQueryCount > 0) {
        issues.add(lokiFailedQueryCount + " Loki queries failed");
    }
    
    if (sourceScannedFileCount == 0) {
        issues.add("No source files scanned");
    } else if (sourceMatchedFileCount == 0) {
        issues.add("Source scanned but no matches found");
    }
    
    if (rcaConfidence < 0.3) {
        issues.add("RCA confidence very low (" + String.format("%.2f", rcaConfidence) + ")");
    } else if (rcaConfidence < 0.6) {
        issues.add("RCA confidence moderate (" + String.format("%.2f", rcaConfidence) + ")");
    }
    
    if (issues.isEmpty()) {
        return "Evidence quality is strong";
    } else {
        return String.join("; ", issues);
    }
}
```

## Expected Output

### Case: FIZZMS-10228 (Current State)

**Golden Path Response:**

```json
{
  "status": "SUCCESS",
  "synthetic": false,
  "steps": {
    "11_summary": {
      "caseId": "ddf5caed-bad7-4eca-b791-5a47869121b0",
      "jiraKey": "FIZZMS-10228",
      "totalEvidence": 9,
      "evidenceByType": {
        "JIRA_ISSUE": 1,
        "REPOSITORY_RESOLUTION": 1,
        "JENKINS_BUILD_CONTEXT": 1,
        "INCIDENT_VERSION": 1,
        "LOKI_LOG": 1,
        "SOURCE_CONTEXT": 1,
        "AI_INPUT_BUNDLE": 1,
        "DETERMINISTIC_ROOT_CAUSE": 1
      },
      "missingEvidence": [],
      "evidenceQuality": {
        "jiraSignalQuality": "UNKNOWN",
        "lokiMatchedRows": 0,
        "lokiFailedQueryCount": 2,
        "lokiTotalQueryCount": 5,
        "correlationIdFound": false,
        "traceIdFound": false,
        "tempoTraceFound": false,
        "sourceScannedFileCount": 0,
        "sourceMatchedFileCount": 0,
        "rcaConfidence": 0.31,
        "rcaClassification": "UNCLASSIFIED",
        "demoReadiness": "WEAK",
        "overallAssessment": "No Loki logs matched; 2 Loki queries failed; No source files scanned; RCA confidence very low (0.31)"
      }
    }
  }
}
```

**Dashboard Display:**

```
Evidence Quality Assessment
===========================

Demo Readiness: ⚠️ WEAK

Jira Signals: UNKNOWN
Loki Logs: 0 matched (2/5 queries failed)
Correlation ID: ❌ Not found
Trace ID: ❌ Not found
Tempo Trace: ❌ Not found
Source Context: 0 files scanned, 0 matched
RCA: UNCLASSIFIED (confidence: 0.31)

Issues:
• No Loki logs matched
• 2 Loki queries failed
• No source files scanned
• RCA confidence very low (0.31)

Recommendation: This case has weak evidence quality. RCA is hypothesis-based.
Not suitable for demo without additional evidence collection.
```

### Case: Strong Evidence (Hypothetical)

```json
{
  "evidenceQuality": {
    "jiraSignalQuality": "STRONG",
    "lokiMatchedRows": 142,
    "lokiFailedQueryCount": 0,
    "lokiTotalQueryCount": 8,
    "correlationIdFound": true,
    "traceIdFound": true,
    "tempoTraceFound": true,
    "sourceScannedFileCount": 1247,
    "sourceMatchedFileCount": 23,
    "rcaConfidence": 0.87,
    "rcaClassification": "DATA_VALIDATION_ERROR",
    "demoReadiness": "STRONG",
    "overallAssessment": "Evidence quality is strong"
  }
}
```

**Dashboard:**

```
Evidence Quality Assessment
===========================

Demo Readiness: ✅ STRONG

Jira Signals: STRONG
Loki Logs: 142 matched (8/8 queries succeeded)
Correlation ID: ✅ Found
Trace ID: ✅ Found
Tempo Trace: ✅ Retrieved
Source Context: 1247 files scanned, 23 matched
RCA: DATA_VALIDATION_ERROR (confidence: 0.87)

Issues: None

Recommendation: This case has strong evidence quality. Suitable for demo.
```

## Files Modified

### 1. New Files

**EvidenceQuality.java** ⭐
```
src/main/java/com/etiya/replaylab/model/EvidenceQuality.java
```
- Evidence quality assessment model
- Captures all quality metrics
- Includes demo readiness

### 2. Modified Files

**GoldenPathOrchestrationService.java** ⭐ MAJOR
- Added `assessEvidenceQuality()` method
- Added `calculateDemoReadiness()` method
- Added `buildOverallAssessment()` method
- Integrated quality assessment into `buildSummary()`
- No changes to core Golden Path flow

**SourceCodeContextService.java** ⭐ MAJOR
- Added `addExpandedCodeVariants()` method
- Added `expandTermVariants()` method
- Expands business terms into code-like variants:
  - camelCase, PascalCase, snake_case, kebab-case
  - Common Java suffixes (Service, Controller, Repository, Entity)
  - Individual words from multi-word terms
- Integrated into `buildSearchTerms()`

## No Changes to Core Flow

**Unchanged:**
- Golden Path orchestration steps
- Evidence collection logic
- RCA computation
- AI bundle creation
- Deterministic RCA analysis

**Added:**
- Quality assessment layer
- Diagnostic metadata
- Demo readiness calculation
- Improved search term expansion

## Testing

### Unit Tests

**Test: Demo Readiness Calculation**
```java
@Test
void testDemoReadinessStrong() {
    // hasStrongLogs && (hasTrace || hasSource) && hasStrongRCA
    String readiness = calculateDemoReadiness(100, true, 5, 0.85, "DATA_ISSUE");
    assertEquals("STRONG", readiness);
}

@Test
void testDemoReadinessMedium() {
    // Some logs, some source, moderate RCA
    String readiness = calculateDemoReadiness(25, false, 3, 0.45, "UNCLASSIFIED");
    assertEquals("MEDIUM", readiness);
}

@Test
void testDemoReadinessWeak() {
    // No logs, no evidence
    String readiness = calculateDemoReadiness(0, false, 0, 0.31, "UNCLASSIFIED");
    assertEquals("WEAK", readiness);
}
```

**Test: Term Expansion**
```java
@Test
void testExpandTermVariants() {
    List<String> variants = expandTermVariants("billing_account");
    
    assertTrue(variants.contains("billing_account"));
    assertTrue(variants.contains("billingAccount"));
    assertTrue(variants.contains("BillingAccount"));
    assertTrue(variants.contains("billing-account"));
    assertTrue(variants.contains("billingaccountservice"));
    assertTrue(variants.contains("BillingAccountService"));
}
```

### Integration Test

**Golden Path with Quality Assessment:**
```powershell
$response = Invoke-RestMethod `
    -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-10228&targetKey=backend" `
    -Method Post

$quality = $response.steps.'11_summary'.evidenceQuality

Write-Host "Demo Readiness: $($quality.demoReadiness)"
Write-Host "Loki Matched: $($quality.lokiMatchedRows)"
Write-Host "Source Matched: $($quality.sourceMatchedFileCount)"
Write-Host "RCA Confidence: $($quality.rcaConfidence)"
Write-Host "Assessment: $($quality.overallAssessment)"
```

**Expected for FIZZMS-10228:**
```
Demo Readiness: WEAK
Loki Matched: 0
Source Matched: 0
RCA Confidence: 0.31
Assessment: No Loki logs matched; 2 Loki queries failed; No source files scanned; RCA confidence very low (0.31)
```

### SQL Verification

```sql
-- Check evidence quality data
SELECT 
    rc.jira_key,
    e.evidence_type,
    e.confidence,
    LENGTH(e.content_text) as content_size
FROM rf_replay_case rc
JOIN rf_evidence e ON rc.id = e.case_id
WHERE rc.jira_key = 'FIZZMS-10228'
ORDER BY e.created_at;
```

## Benefits

1. **Transparency**: Clear indication of evidence strength
2. **Demo Readiness**: Know which cases are suitable for demo
3. **Diagnostics**: Understand why evidence is weak
4. **Improved Search**: Better source code matching via term expansion
5. **No Breaking Changes**: Existing functionality unchanged
6. **Actionable**: Issues clearly identified with counts and reasons

## Recommendations

### For FIZZMS-10228

**Current State:** WEAK demo readiness

**Actions:**
1. Investigate why Loki queries failed (check logs)
2. Verify source checkout succeeded
3. Check if business terms in Jira match actual code structure
4. Consider manual log collection for demo
5. Use a different case with stronger evidence for demo

### For Future Cases

**STRONG Demo Candidates:**
- Loki matched > 50 logs
- Correlation/Trace IDs present
- Source code matches found
- RCA confidence > 0.7

**MEDIUM Demo Candidates:**
- Some logs (10-50)
- Some source matches
- Moderate RCA confidence (0.3-0.7)

**WEAK Cases:**
- Avoid for demo
- Use for testing and diagnostics
- Good for validating "no evidence invention" behavior

## Build and Test

```powershell
mvn clean compile -DskipTests
mvn test
```

**Expected:** All tests pass, no compilation errors, evidence quality included in Golden Path response.

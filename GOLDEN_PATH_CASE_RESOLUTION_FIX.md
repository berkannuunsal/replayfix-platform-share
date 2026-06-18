# Golden Path Case Resolution Fix

## Problem Fixed
Golden Path was reusing cases with different `targetKey` values when only matching `jiraKey`.

## Solution Implemented

### 1. Repository Enhancement
Added new query method in `ReplayCaseRepository`:
```java
Optional<ReplayCaseEntity> findFirstByJiraKeyAndTargetKey(String jiraKey, String targetKey);
```

### 2. Controller Update
Added `forceNew` parameter to `/api/v1/golden-path/execute`:
```java
@PostMapping("/execute")
public ResponseEntity<Map<String, Object>> execute(
    @RequestParam String jiraKey,
    @RequestParam String targetKey,
    @RequestParam(defaultValue = "false") boolean forceNew
)
```

### 3. Target Validation
Added validation step (Step 0) to check target exists in `replay-targets.yml`:
- If target not found: fail fast with `TARGET_NOT_CONFIGURED`
- Returns list of available targets
- Prevents Jenkins/Loki collection with invalid target

### 4. Case Resolution Logic
Updated `findOrCreateCase` method:
- **Default behavior** (`forceNew=false`): Find case matching BOTH `jiraKey` AND `targetKey`
- **Force new** (`forceNew=true`): Create new case even if old cases exist
- New cases always have:
  - `synthetic=false`
  - `target_key=requestedTargetKey`
  - `jenkins_job_name=null`
  - `jenkins_build_number=null`

### 5. Enhanced Response
Golden Path response now includes:
```json
{
  "jiraKey": "FIZZMS-8346",
  "requestedTargetKey": "fizz-marketplace-service",
  "resolvedTargetKey": "fizz-marketplace-service",
  "caseId": "uuid",
  "reusedExistingCase": true/false,
  "forceNew": false,
  "timestamp": "2026-06-17T09:00:00Z",
  "status": "SUCCESS",
  "synthetic": false,
  "steps": {
    "0_target_validation": {
      "result": "SUCCESS",
      "targetKey": "fizz-marketplace-service",
      "targetExists": true
    },
    "1_case_resolution": {
      "result": "SUCCESS",
      "action": "FOUND_EXISTING" | "CREATED_NEW" | "FORCED_NEW",
      "caseId": "uuid",
      "targetKey": "fizz-marketplace-service",
      "synthetic": false,
      "reusedExistingCase": true/false
    },
    ...
  }
}
```

## Usage Examples

### Scenario 1: Normal case reuse (default)
```powershell
# First call - creates new case for FIZZMS-8346 + fizz-marketplace-service
$r1 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$caseId1 = $r1.caseId
# Response: reusedExistingCase=false, action=CREATED_NEW

# Second call - reuses same case
$r2 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$caseId2 = $r2.caseId
# Response: reusedExistingCase=true, action=FOUND_EXISTING
# caseId1 == caseId2 ✅
```

### Scenario 2: Different targetKey creates new case
```powershell
# First call - fizz-marketplace-service
$r1 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$caseId1 = $r1.caseId

# Second call - different target: order-service
$r2 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=order-service" -Method Post
$caseId2 = $r2.caseId
# Response: reusedExistingCase=false, action=CREATED_NEW
# caseId1 != caseId2 ✅
```

### Scenario 3: Force new case
```powershell
# First call
$r1 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$caseId1 = $r1.caseId

# Second call with forceNew=true
$r2 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service&forceNew=true" -Method Post
$caseId2 = $r2.caseId
# Response: reusedExistingCase=false, action=FORCED_NEW
# caseId1 != caseId2 ✅
```

### Scenario 4: Invalid target fails fast
```powershell
$response = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=invalid-target" -Method Post
# Response:
# {
#   "status": "FAILED",
#   "failureReason": "TARGET_NOT_CONFIGURED",
#   "steps": {
#     "0_target_validation": {
#       "result": "FAILED",
#       "targetKey": "invalid-target",
#       "targetExists": false,
#       "availableTargets": ["fizz-marketplace-service", "order-service", ...]
#     }
#   }
# }
```

## Database Query to Verify
```sql
-- Check cases for same jiraKey with different targets
SELECT 
    id,
    jira_key,
    target_key,
    status,
    synthetic,
    jenkins_job_name,
    jenkins_build_number,
    created_at
FROM rf_case
WHERE jira_key = 'FIZZMS-8346'
ORDER BY created_at DESC;

-- Result should show separate cases for each targetKey
```

## Key Behaviors

### ✅ Correct Behavior (After Fix)
1. Case lookup matches BOTH `jiraKey` AND `targetKey`
2. Different targets create separate cases
3. `forceNew=true` always creates new case
4. Invalid target fails immediately
5. Response clearly indicates case reuse status

### ❌ Previous Behavior (Bug)
1. Case lookup matched only `jiraKey`
2. Different targets reused same case
3. No target validation
4. Unclear if case was reused

## Testing Checklist
- [ ] Same jiraKey + same targetKey → reuses case
- [ ] Same jiraKey + different targetKey → creates new case
- [ ] forceNew=true → creates new case always
- [ ] Invalid targetKey → fails with TARGET_NOT_CONFIGURED
- [ ] Response includes reusedExistingCase flag
- [ ] Response includes requestedTargetKey and resolvedTargetKey
- [ ] New cases have synthetic=false
- [ ] New cases have Jenkins fields null

## Files Modified
1. `ReplayCaseRepository.java` - Added `findFirstByJiraKeyAndTargetKey`
2. `GoldenPathController.java` - Added `forceNew` parameter
3. `GoldenPathOrchestrationService.java` - Complete case resolution logic rewrite
4. `ReplayCaseEntity.java` - Added Jenkins fields (previous fix)
5. `V5__add_jenkins_fields_to_case.sql` - Database migration (previous fix)

## Demo Commands
```powershell
# Test 1: Create case for FIZZMS-8346 + fizz-marketplace-service
$r1 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
$r1 | ConvertTo-Json -Depth 10

# Test 2: Reuse same case
$r2 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service" -Method Post
Write-Host "Case reused: $($r2.reusedExistingCase)"

# Test 3: Different target creates new case
$r3 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=order-service" -Method Post
Write-Host "New case created: $($r3.caseId -ne $r1.caseId)"

# Test 4: Force new case
$r4 = Invoke-RestMethod -Uri "http://localhost:8088/api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service&forceNew=true" -Method Post
Write-Host "Forced new case: $($r4.caseId -ne $r1.caseId)"
```

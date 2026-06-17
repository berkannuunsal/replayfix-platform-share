# Golden Path V2 - Complete Implementation Plan

## Overview
Golden Path v2 adds Rovo integration, method commit history analysis, and automated fix workflow with human approvals targeting `test2` branch.

## Branch Strategy
- **Target Branch**: `test2`
- **Fix Branch Format**: `bugfix/{jiraKey}-{short-description}`
- **Example**: `bugfix/FIZZMS-8346-replayfix`
- **PR Target**: `test2` (DRAFT mode)
- **NO MERGE, NO DEPLOY**

## Components Created

### 1. Rovo Incident Commander Integration ✅

**Models:**
- `RovoIncidentContext` - Sanitized context for Rovo
- `RovoAnalysisSubmission` - Rovo RCA submission

**Controller:**
- `RovoIncidentCommanderController`
  - `GET /api/v1/rovo/incidents/{jiraKey}/context`
  - `POST /api/v1/rovo/incidents/{jiraKey}/analysis`

**Service:**
- `RovoIncidentCommanderService`
  - Builds sanitized context from evidence
  - Persists Rovo RCA as `ROVO_RCA` evidence

**Evidence Types Added:**
- `ROVO_RCA` - source: `rovo-incident-commander`
- `METHOD_COMMIT_HISTORY` - source: `bitbucket-method-commit-analyzer`
- `JENKINS_BUILD` - General Jenkins build evidence

### 2. Branch Strategy ✅

**Model:**
- `BranchStrategy` - Encapsulates branch configuration

**Configuration:**
```java
BranchStrategy strategy = BranchStrategy.defaultStrategy("FIZZMS-8346");
// Returns:
// - targetBranch: "test2"
// - fixBranchName: "bugfix/FIZZMS-8346-replayfix"
// - fetchBeforeCreate: true
// - useExactTargetHead: true
```

### 3. Approval Workflow ✅

**Approval Types:**
- `HUMAN_APPROVAL_FIX_PROPOSAL` - Before generating fix
- `HUMAN_APPROVAL_DRAFT_PR` - Before creating PR

**Required Services (TO BE IMPLEMENTED):**

```java
@Service
public class ApprovalWorkflowService {
    
    /**
     * Request human approval for fix proposal.
     * Returns approval ID for tracking.
     */
    public UUID requestFixProposalApproval(
        UUID caseId,
        String proposedFix,
        List<String> affectedFiles,
        List<String> affectedMethods,
        RovoAnalysisSubmission rovoRca
    ) {
        // Create approval request
        // Notify via notification system
        // Return approval ID
    }
    
    /**
     * Check approval status.
     */
    public ApprovalStatus checkApproval(UUID approvalId) {
        // Query approval status
        // Returns: PENDING, APPROVED, REJECTED, EXPIRED
    }
    
    /**
     * Request PR creation approval.
     */
    public UUID requestDraftPrApproval(
        UUID caseId,
        String branchName,
        String testResults,
        String commitSha
    ) {
        // Create PR approval request
    }
}
```

### 4. Method Commit History Analysis (TO BE IMPLEMENTED)

**Model Created:**
- `MethodCommitHistory` - Tracks method-level commits

**Required Service:**

```java
@Service
public class MethodCommitHistoryService {
    
    /**
     * Analyze method commit history from Bitbucket.
     * 
     * @param caseId Case ID
     * @param filePath File path in repository
     * @param className Class name
     * @param methodName Method name
     * @param lookbackDays Lookback period (default: 45)
     * @return Method commit history with risk scoring
     */
    public MethodCommitHistory analyzeMethodHistory(
        UUID caseId,
        String repository,
        String branch, // "test2"
        String filePath,
        String className,
        String methodName,
        int lookbackDays
    ) {
        // 1. Get file commits from Bitbucket (last 45 days)
        // 2. For each commit, get diff
        // 3. Parse method line ranges
        // 4. Check if commit touched method
        // 5. Extract Jira key from commit message
        // 6. Calculate risk score based on:
        //    - Time proximity to incident
        //    - Direct method change
        //    - Related symptoms (auth, token, config keywords)
        //    - Ancestry with incident commit
        // 7. Return structured history
    }
    
    /**
     * Score commit as potential root cause.
     */
    private double calculateRiskScore(
        MethodCommit commit,
        Instant incidentTime,
        String incidentCommitSha,
        List<String> symptoms
    ) {
        double score = 0.0;
        
        // Time proximity (max 40 points)
        long hoursDiff = Duration.between(commit.timestamp(), incidentTime).toHours();
        if (hoursDiff < 24) score += 40;
        else if (hoursDiff < 72) score += 30;
        else if (hoursDiff < 168) score += 20;
        else score += 10;
        
        // Direct method change (30 points)
        if (commit.directMethodChange()) score += 30;
        
        // Symptom keywords (20 points)
        String message = commit.commitMessage().toLowerCase();
        if (symptoms.stream().anyMatch(message::contains)) score += 20;
        
        // Incident ancestry (10 points)
        if (isAncestor(commit.commitSha(), incidentCommitSha)) score += 10;
        
        return score / 100.0; // Normalize to 0-1
    }
}
```

**Evidence Storage:**
```json
{
  "evidenceType": "METHOD_COMMIT_HISTORY",
  "source": "bitbucket-method-commit-analyzer",
  "body": {
    "repository": "etiya/fizz-marketplace",
    "branch": "test2",
    "filePath": "src/main/java/AuthService.java",
    "className": "AuthService",
    "methodName": "validateToken",
    "lookbackDays": 45,
    "commits": [
      {
        "commitSha": "abc123",
        "author": "developer@example.com",
        "timestamp": "2026-06-10T14:30:00Z",
        "commitMessage": "fix: Update token validation logic",
        "relatedJiraKey": "FIZZMS-8200",
        "relatedPullRequest": "PR-456",
        "directMethodChange": true,
        "riskScore": 0.85,
        "beforeIncident": true
      }
    ]
  }
}
```

### 5. Fix Generation Workflow (TO BE IMPLEMENTED)

```java
@Service
public class FixGenerationWorkflowService {
    
    public UUID executeFixWorkflow(UUID caseId) {
        // 1. Load Rovo RCA
        RovoAnalysisSubmission rovoRca = loadRovoRca(caseId);
        
        // 2. Identify affected files/methods
        List<SourceLocation> locations = identifySourceLocations(caseId, rovoRca);
        
        // 3. Analyze method commit history
        for (SourceLocation location : locations) {
            MethodCommitHistory history = methodCommitHistoryService.analyze(
                caseId, repo, "test2", location.file, location.class, location.method, 45
            );
            persistMethodHistory(caseId, history);
        }
        
        // 4. Request FIX_PROPOSAL approval
        UUID approvalId = approvalService.requestFixProposalApproval(
            caseId, rovoRca.minimumFixDirection(), ...
        );
        
        // 5. Wait for approval (external process)
        return approvalId;
    }
    
    public void continueAfterFixApproval(UUID caseId, UUID approvalId) {
        // 6. Verify approval
        ApprovalStatus status = approvalService.checkApproval(approvalId);
        if (status != ApprovalStatus.APPROVED) {
            throw new IllegalStateException("Fix proposal not approved");
        }
        
        // 7. Create fix branch
        BranchStrategy strategy = BranchStrategy.defaultStrategy(jiraKey);
        gitService.fetchOrigin("test2");
        String branchSha = gitService.createBranchFromHead(strategy.fixBranchName(), "test2");
        
        // 8. Generate regression test
        String test = testGeneratorService.generate(caseId);
        gitService.commitFile(testFile, "Add regression test");
        
        // 9. Apply minimum fix
        String fix = fixGeneratorService.generate(caseId, rovoRca);
        gitService.commitFile(sourceFile, "Apply minimum fix");
        
        // 10. Compile and test
        CompileResult compileResult = compileService.compile(caseId);
        TestResult testResult = testService.runTests(caseId, relevantTests);
        
        if (!testResult.success()) {
            log.error("Tests failed - NOT pushing branch");
            return;
        }
        
        // 11. Request DRAFT_PR approval
        UUID prApprovalId = approvalService.requestDraftPrApproval(
            caseId, strategy.fixBranchName(), testResult, branchSha
        );
    }
    
    public void continueAfterPrApproval(UUID caseId, UUID approvalId) {
        // 12. Verify PR approval
        ApprovalStatus status = approvalService.checkApproval(approvalId);
        if (status != ApprovalStatus.APPROVED) {
            throw new IllegalStateException("PR creation not approved");
        }
        
        // 13. Push branch
        gitService.pushBranch(strategy.fixBranchName());
        
        // 14. Create DRAFT PR
        PullRequest pr = bitbucketService.createDraftPr(
            strategy.fixBranchName(),
            "test2",
            buildPrDescription(caseId)
        );
        
        log.info("DRAFT_PR_CREATED: caseId={}, prUrl={}", caseId, pr.url());
    }
    
    private String buildPrDescription(UUID caseId) {
        return """
            ## ReplayFix Automated Fix
            
            **Jira Issue**: %s
            **Case ID**: %s
            **Evidence Summary**: ...
            **Rovo RCA**: ...
            **Suspected Commit History**: ...
            **Probable Root Cause**: ...
            **Changed Files/Methods**: ...
            **Generated Regression Test**: ...
            **Validation Results**: PASSED
            **Confidence**: 0.85
            **Missing Evidence**: None
            **Approval**: HUMAN_APPROVED (Fix Proposal + Draft PR)
            **Rollback**: See commit history
            
            ⚠️ **DRAFT PR - DO NOT MERGE WITHOUT REVIEW**
            """.formatted(jiraKey, caseId, ...);
    }
}
```

### 6. Dashboard Updates (TO BE IMPLEMENTED)

Add "Recent Method Changes" section to dashboard:

```java
@Service
public class IncidentDashboardService {
    
    // Add new section
    public DashboardMethodChanges getMethodChanges(UUID caseId) {
        List<EvidenceEntity> historyEvidence = evidenceRepository
            .findByCaseIdAndType(caseId, EvidenceType.METHOD_COMMIT_HISTORY);
        
        // Parse and aggregate
        // Return timeline of changes
        // Highlight suspected commits
    }
}
```

**Dashboard UI:**
```
┌─ Recent Method Changes (Last 45 Days) ─────────────────┐
│                                                         │
│  Timeline: ━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                      ↑ Incident                        │
│                                                         │
│  ● 2026-06-10 14:30:00 | abc123 | AuthService.valid...│
│    Risk: ████████░░ 85%                                │
│    Author: dev@example.com                             │
│    Message: fix: Update token validation logic         │
│    Jira: FIZZMS-8200 | PR: PR-456                     │
│                                                         │
│  ● 2026-06-05 09:15:00 | def456 | AuthService.valid...│
│    Risk: ███░░░░░░░ 30%                                │
│    ...                                                  │
└─────────────────────────────────────────────────────────┘
```

## API Endpoints Summary

### Rovo Integration
- `GET /api/v1/rovo/incidents/{jiraKey}/context` ✅
- `POST /api/v1/rovo/incidents/{jiraKey}/analysis` ✅

### Golden Path Orchestration
- `POST /api/v1/golden-path/execute?jiraKey={key}&targetKey={target}` ✅

### Fix Workflow (TO BE ADDED)
- `POST /api/v1/cases/{caseId}/fix-workflow/start`
- `POST /api/v1/cases/{caseId}/fix-workflow/approve-fix/{approvalId}`
- `POST /api/v1/cases/{caseId}/fix-workflow/approve-pr/{approvalId}`
- `GET /api/v1/cases/{caseId}/fix-workflow/status`

### Method History (TO BE ADDED)
- `POST /api/v1/cases/{caseId}/method-history/analyze`
- `GET /api/v1/cases/{caseId}/method-history`

## Usage Flow

```bash
# 1. Execute golden path (collect evidence)
POST /api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service

# 2. Rovo gets context
GET /api/v1/rovo/incidents/FIZZMS-8346/context

# 3. Rovo submits RCA
POST /api/v1/rovo/incidents/FIZZMS-8346/analysis
{
  "executiveSummary": "Auth token validation failure",
  "probableRootCause": "Token expiry check missing",
  "confidence": 0.85,
  ...
}

# 4. Start fix workflow
POST /api/v1/cases/{caseId}/fix-workflow/start
# Returns: { "approvalId": "uuid", "stage": "AWAITING_FIX_PROPOSAL_APPROVAL" }

# 5. Human reviews and approves fix proposal
POST /api/v1/cases/{caseId}/fix-workflow/approve-fix/{approvalId}
# System creates branch, generates test/fix, runs tests

# 6. If tests pass, requests PR approval
# Returns: { "approvalId": "uuid2", "stage": "AWAITING_PR_APPROVAL" }

# 7. Human reviews test results and approves PR
POST /api/v1/cases/{caseId}/fix-workflow/approve-pr/{approvalId2}
# System pushes branch and creates DRAFT PR

# 8. Check final status
GET /api/v1/cases/{caseId}/fix-workflow/status
{
  "stage": "COMPLETED",
  "branchName": "replayfix/FIZZMS-8346-auth-token-fix",
  "pullRequestUrl": "https://bitbucket.../pull-requests/123",
  "pullRequestStatus": "DRAFT",
  "approvals": [
    { "type": "FIX_PROPOSAL", "approved": true, "approvedBy": "user@example.com" },
    { "type": "DRAFT_PR", "approved": true, "approvedBy": "user@example.com" }
  ]
}
```

## Success Criteria

✅ **Implemented:**
- Rovo integration contracts
- Evidence types (ROVO_RCA, METHOD_COMMIT_HISTORY)
- Branch strategy model
- Approval types enum
- Golden path orchestration service

⏳ **To Implement:**
- Method commit history service
- Approval workflow service
- Fix generation workflow
- Dashboard method changes section
- Integration tests

🎯 **Demo Requirements:**
- Real Jira: FIZZMS-8346
- Real repository from Bitbucket
- Branch: test2
- Real method from source
- Real 45-day commit history
- Real Jenkins commit
- Fix branch from origin/test2
- PR target: test2
- PR status: DRAFT
- NO merge, NO deploy
- synthetic=false throughout

## Next Steps

1. ✅ **Compile current code**
   ```bash
   mvn clean compile -DskipTests
   ```

2. ⏳ **Implement remaining services**
   - MethodCommitHistoryService
   - ApprovalWorkflowService
   - FixGenerationWorkflowService

3. ⏳ **Add API endpoints**
   - Fix workflow controller
   - Method history controller

4. ⏳ **Test with real case**
   - Use FIZZMS-8346 or NTF build 309
   - Verify all evidence collection
   - Test Rovo integration
   - Execute fix workflow

5. ⏳ **Dashboard updates**
   - Add method changes timeline
   - Show approval status
   - Display PR information

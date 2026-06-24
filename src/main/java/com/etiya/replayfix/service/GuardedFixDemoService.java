package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ApprovedWritePlanResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushRequest;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.GuardedFixDemoPreviewRequest;
import com.etiya.replayfix.api.dto.GuardedFixDemoPreviewResponse;
import com.etiya.replayfix.api.dto.GuardedFixDemoStageResponse;
import com.etiya.replayfix.api.dto.GuardedFixDemoTestOnlyPrRequest;
import com.etiya.replayfix.api.dto.GuardedFixDemoTestOnlyPrResponse;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.etiya.replayfix.model.RegressionTestDraftResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GuardedFixDemoService {

    private static final String SOURCE =
            "replayfix-guarded-fix-demo-test-only-pr";
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "password",
            "token",
            "secret",
            "apiKey",
            "privateKey",
            "Authorization",
            "Cookie"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final CodeChangeAdvisoryService advisoryService;
    private final PatchPlanCandidateService patchPlanService;
    private final RegressionTestDraftService regressionTestDraftService;
    private final ApprovedWritePlanService approvedWritePlanService;
    private final WorkspaceWriteService workspaceWriteService;
    private final BitbucketWorkspacePushService workspacePushService;
    private final BitbucketPullRequestRealActionService pullRequestService;
    private final WorkspaceGitOperations gitOperations;
    private final BitbucketClient bitbucketClient;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;
    private final Path repositoryRoot;

    @Autowired
    public GuardedFixDemoService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            CodeChangeAdvisoryService advisoryService,
            PatchPlanCandidateService patchPlanService,
            RegressionTestDraftService regressionTestDraftService,
            ApprovedWritePlanService approvedWritePlanService,
            WorkspaceWriteService workspaceWriteService,
            BitbucketWorkspacePushService workspacePushService,
            BitbucketPullRequestRealActionService pullRequestService,
            WorkspaceGitOperations gitOperations,
            BitbucketClient bitbucketClient,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this(
                caseRepository,
                evidenceRepository,
                advisoryService,
                patchPlanService,
                regressionTestDraftService,
                approvedWritePlanService,
                workspaceWriteService,
                workspacePushService,
                pullRequestService,
                gitOperations,
                bitbucketClient,
                properties,
                objectMapper,
                Path.of("").toAbsolutePath()
        );
    }

    GuardedFixDemoService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            CodeChangeAdvisoryService advisoryService,
            PatchPlanCandidateService patchPlanService,
            RegressionTestDraftService regressionTestDraftService,
            ApprovedWritePlanService approvedWritePlanService,
            WorkspaceWriteService workspaceWriteService,
            BitbucketWorkspacePushService workspacePushService,
            BitbucketPullRequestRealActionService pullRequestService,
            WorkspaceGitOperations gitOperations,
            BitbucketClient bitbucketClient,
            ReplayFixProperties properties,
            ObjectMapper objectMapper,
            Path repositoryRoot
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.advisoryService = advisoryService;
        this.patchPlanService = patchPlanService;
        this.regressionTestDraftService = regressionTestDraftService;
        this.approvedWritePlanService = approvedWritePlanService;
        this.workspaceWriteService = workspaceWriteService;
        this.workspacePushService = workspacePushService;
        this.pullRequestService = pullRequestService;
        this.gitOperations = gitOperations;
        this.bitbucketClient = bitbucketClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public GuardedFixDemoPreviewResponse preview(
            UUID caseId,
            GuardedFixDemoPreviewRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        GuardedFixDemoPreviewRequest safe = safePreviewRequest(
                replayCase,
                request
        );
        List<GuardedFixDemoStageResponse> stages = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        PatchPlanCandidateResponse patchPlan = null;
        RegressionTestDraftResponse regressionDraft = null;
        ApprovedWritePlanResponse writePlan = null;

        if (enabled(safe.includeCodeAdvisory())) {
            try {
                CodeChangeAdvisoryEvaluationSummaryResponse summary =
                        advisoryService.summary(caseId);
                stages.add(stage(
                        "CODE_ADVISORY",
                        summary.caseAdvisoryStatus(),
                        "advisories=" + summary.advisoryGeneratedCount()
                                + ", shouldProceedToPatch="
                                + summary.shouldProceedToPatchCount(),
                        List.of(),
                        List.of(),
                        summary.advisoryGeneratedCount() == 0
                                ? List.of("Run code advisory orchestration before patch planning.")
                                : List.of()
                ));
            } catch (Exception exception) {
                stages.add(unavailableStage("CODE_ADVISORY"));
                warnings.add("CODE_ADVISORY_UNAVAILABLE");
            }
        }

        if (enabled(safe.includePatchPlan())) {
            try {
                patchPlan = patchPlanService.candidate(
                        caseId,
                        false,
                        true,
                        enabled(safe.includeRegressionTestDraft())
                );
                stages.add(stage(
                        "PATCH_PLAN",
                        patchPlan.patchPlanStatus(),
                        patchPlan.targetClass() + "#"
                                + patchPlan.targetMethod()
                                + " changeType="
                                + patchPlan.recommendedChangeType(),
                        patchPlan.missingEvidence(),
                        patchPlan.warnings(),
                        List.of("Human review required before any patch write.")
                ));
            } catch (Exception exception) {
                stages.add(unavailableStage("PATCH_PLAN"));
                blockers.add("PATCH_PLAN_UNAVAILABLE");
            }
        }

        if (enabled(safe.includeRegressionTestDraft())) {
            try {
                regressionDraft = regressionTestDraftService.draft(
                        caseId,
                        false,
                        3
                );
                stages.add(stage(
                        "REGRESSION_TEST_DRAFT",
                        regressionDraft.status(),
                        regressionDraft.selectedTestType()
                                + " "
                                + regressionDraft.targetEndpoint(),
                        List.of(),
                        regressionDraft.warnings(),
                        List.of("Review generated regression test before execution.")
                ));
            } catch (Exception exception) {
                stages.add(unavailableStage("REGRESSION_TEST_DRAFT"));
                blockers.add("REGRESSION_TEST_DRAFT_MISSING");
            }
        }

        if (enabled(safe.includeApprovedWritePlan())) {
            try {
                writePlan = approvedWritePlanService.plan(
                        caseId,
                        null,
                        true,
                        true,
                        true
                );
                stages.add(stage(
                        "APPROVED_WRITE_PLAN",
                        writePlan.writePlanStatus(),
                        "plannedFiles=" + writePlan.plannedFiles().size()
                                + ", dryRun=" + writePlan.dryRun(),
                        writePlan.missingEvidence(),
                        writePlan.warnings(),
                        List.of("Approval is required before workspace write.")
                ));
            } catch (Exception exception) {
                stages.add(unavailableStage("APPROVED_WRITE_PLAN"));
                blockers.add("APPROVED_WRITE_PLAN_UNAVAILABLE");
            }
        }

        if (enabled(safe.includeWorkspaceWrite())) {
            try {
                WorkspaceWriteResponse workspaceWrite =
                        workspaceWriteService.preview(
                                caseId,
                                true,
                                true,
                                true
                        );
                stages.add(stage(
                        "WORKSPACE_WRITE",
                        workspaceWrite.writeStatus(),
                        "plannedFiles="
                                + workspaceWrite.filesPlanned().size()
                                + ", filesWritten="
                                + workspaceWrite.filesWritten().size(),
                        List.of(),
                        workspaceWrite.warnings(),
                        List.of("Apply workspace write only after approval.")
                ));
            } catch (Exception exception) {
                stages.add(unavailableStage("WORKSPACE_WRITE"));
                blockers.add("WORKSPACE_WRITE_PREVIEW_UNAVAILABLE");
            }
        }

        if (enabled(safe.includeWorkspacePush())) {
            try {
                BitbucketWorkspacePushResponse workspacePush =
                        workspacePushService.preview(
                                caseId,
                                workspacePushRequest(safe, false, false)
                        );
                stages.add(stage(
                        "WORKSPACE_PUSH",
                        workspacePush.blockers().isEmpty()
                                ? "PREVIEW_READY"
                                : "BLOCKED",
                        "bugfix=" + workspacePush.bugfixBranch()
                                + ", integration="
                                + workspacePush.integrationBranch(),
                        workspacePush.blockers(),
                        workspacePush.warnings(),
                        workspacePush.nextActions()
                ));
                blockers.addAll(workspacePush.blockers());
                warnings.addAll(workspacePush.warnings());
            } catch (Exception exception) {
                stages.add(unavailableStage("WORKSPACE_PUSH"));
                blockers.add("WORKSPACE_PUSH_PREVIEW_UNAVAILABLE");
            }
        }

        if (enabled(safe.includePrPreview())) {
            try {
                BitbucketPullRequestResponse prPreview =
                        pullRequestService.preview(
                                caseId,
                                pullRequestRequest(safe, false, false)
                        );
                stages.add(stage(
                        "BITBUCKET_PR",
                        prPreview.blockers().isEmpty()
                                ? "PREVIEW_READY"
                                : "BLOCKED",
                        prPreview.sourceBranch()
                                + " -> "
                                + prPreview.targetBranch(),
                        prPreview.blockers(),
                        prPreview.warnings(),
                        prPreview.nextActions()
                ));
                blockers.addAll(prPreview.blockers());
                warnings.addAll(prPreview.warnings());
            } catch (Exception exception) {
                stages.add(unavailableStage("BITBUCKET_PR"));
                blockers.add("BITBUCKET_PR_PREVIEW_UNAVAILABLE");
            }
        }

        String recommendedPath = recommendedPath(
                safe,
                patchPlan,
                regressionDraft,
                writePlan
        );
        if ("PREVIEW_ONLY".equals(recommendedPath)
                && regressionDraft == null
                && patchPlan != null
                && noSourceFix(patchPlan)) {
            blockers.add("REGRESSION_TEST_DRAFT_MISSING");
        }
        String demoStatus = demoStatus(blockers, stages);
        return new GuardedFixDemoPreviewResponse(
                replayCase.getId(),
                firstNonBlank(safe.jiraKey(), replayCase.getJiraKey()),
                demoStatus,
                recommendedPath,
                stages,
                unique(blockers),
                unique(warnings),
                recommendedNextAction(recommendedPath, blockers),
                Instant.now()
        );
    }

    @Transactional
    public GuardedFixDemoTestOnlyPrResponse executeTestOnlyPr(
            UUID caseId,
            GuardedFixDemoTestOnlyPrRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        GuardedFixDemoTestOnlyPrRequest safe = safeExecuteRequest(
                replayCase,
                request
        );
        validateExecute(safe);
        List<String> blockers = branchBlockers(
                safe.bugfixBranch(),
                safe.integrationBranch()
        );
        if (!blockers.isEmpty()) {
            return testOnlyResponse(
                    replayCase,
                    safe,
                    "",
                    "",
                    "",
                    "",
                    false,
                    blockers,
                    List.of(),
                    List.of("Fix branch names before executing demo PR flow.")
            );
        }

        Path workspaceRoot = workspaceRoot(replayCase, safe.repositorySlug());
        String generatedRelativePath = generatedTestRelativePath(safe.jiraKey());
        Path generatedPath = workspaceRoot.resolve(generatedRelativePath)
                .normalize();
        if (!generatedPath.startsWith(workspaceRoot)) {
            return testOnlyResponse(
                    replayCase,
                    safe,
                    generatedRelativePath,
                    "",
                    "",
                    "",
                    false,
                    List.of("WORKSPACE_PATH_OUTSIDE_REPLAYFIX_WORK"),
                    List.of(),
                    List.of("Use only workspace-relative test paths.")
            );
        }
        try {
            Files.createDirectories(generatedPath.getParent());
            Files.writeString(
                    generatedPath,
                    generatedTestContent(safe.jiraKey()),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            return testOnlyResponse(
                    replayCase,
                    safe,
                    generatedRelativePath,
                    "",
                    "",
                    "",
                    false,
                    List.of("TEST_ONLY_FILE_WRITE_FAILED"),
                    List.of(),
                    List.of("Prepare isolated workspace before retrying.")
            );
        }

        WorkspaceGitOperations.WorkspaceGitPushResult pushed =
                gitOperations.pushApprovedChanges(
                        workspaceRoot,
                        safe.developmentBaseBranch(),
                        safe.environmentTargetBranch(),
                        safe.bugfixBranch(),
                        safe.integrationBranch(),
                        safe.commitMessage()
                );
        if (!isBlank(pushed.error())) {
            String blocker = pushed.mergeConflict()
                    || pushed.error().contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN")
                    ? "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"
                    : "WORKSPACE_PUSH_FAILED";
            return testOnlyResponse(
                    replayCase,
                    safe,
                    generatedRelativePath,
                    pushed.commitSha(),
                    "",
                    "",
                    false,
                    List.of(blocker),
                    nonBlank(pushed.warning()),
                    List.of("Resolve workspace push blocker before draft PR creation.")
            );
        }

        List<String> prBlockers = new ArrayList<>();
        BitbucketBranchCheckResult source = bitbucketClient.branchExists(
                safe.projectKey(),
                safe.repositorySlug(),
                safe.integrationBranch()
        );
        if (!source.exists()) {
            prBlockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult target = bitbucketClient.branchExists(
                safe.projectKey(),
                safe.repositorySlug(),
                safe.targetPrBranch()
        );
        if (!target.exists()) {
            prBlockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }
        if (!prBlockers.isEmpty()) {
            return testOnlyResponse(
                    replayCase,
                    safe,
                    generatedRelativePath,
                    pushed.commitSha(),
                    "",
                    "",
                    false,
                    prBlockers,
                    concat(source.warnings(), target.warnings()),
                    List.of("Create draft PR after source and target branches are visible in Bitbucket.")
            );
        }

        PullRequestResult pr = bitbucketClient.createPullRequest(
                safe.projectKey(),
                safe.repositorySlug(),
                safe.integrationBranch(),
                safe.targetPrBranch(),
                "[DRAFT] ReplayFix " + safe.jiraKey()
                        + " demo regression test",
                prDescription(replayCase, safe, generatedRelativePath),
                List.of()
        );
        GuardedFixDemoTestOnlyPrResponse response = testOnlyResponse(
                replayCase,
                safe,
                generatedRelativePath,
                pushed.commitSha(),
                nullToBlank(pr.url()),
                nullToBlank(pr.id()),
                true,
                List.of(),
                concat(source.warnings(), target.warnings()),
                List.of("Review draft PR. Do not merge until human validation completes.")
        );
        replayCase.setGeneratedBranch(safe.integrationBranch());
        replayCase.setPullRequestUrl(response.pullRequestUrl());
        caseRepository.save(replayCase);
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private void validateExecute(GuardedFixDemoTestOnlyPrRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.requestedBy())) {
            errors.add("REQUESTED_BY_REQUIRED");
        }
        if (!request.confirmExecute()) {
            errors.add("CONFIRM_EXECUTE_REQUIRED");
        }
        if (properties.getRealActions().isRequireGuardrailsAccepted()
                && !request.guardrailsAccepted()) {
            errors.add("GUARDRAILS_ACCEPTED_REQUIRED");
        }
        if (!request.testOnly()) {
            errors.add("TEST_ONLY_REQUIRED");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.join(",", errors)
            );
        }
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isBitbucketBranchCreateEnabled()
                || !properties.getRealActions().isBitbucketPushEnabled()
                || !properties.getRealActions().isBitbucketPrCreateEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REAL_ACTIONS_DISABLED"
            );
        }
    }

    private String recommendedPath(
            GuardedFixDemoPreviewRequest request,
            PatchPlanCandidateResponse patchPlan,
            RegressionTestDraftResponse regressionDraft,
            ApprovedWritePlanResponse writePlan
    ) {
        if (patchPlan != null && !noSourceFix(patchPlan)) {
            return "SOURCE_FIX_PR";
        }
        if (enabled(request.preferTestOnlyPrWhenSourceFixNotApproved())
                && (regressionDraft != null
                || (writePlan != null && !writePlan.plannedTests().isEmpty()))) {
            return "TEST_ONLY_PR";
        }
        return "PREVIEW_ONLY";
    }

    private boolean noSourceFix(PatchPlanCandidateResponse patchPlan) {
        if (patchPlan.shouldProceedToPatch()) {
            return false;
        }
        Map<String, Object> change = patchPlan.recommendedCodeChange();
        boolean writesCode = Boolean.parseBoolean(
                String.valueOf(change.getOrDefault("writesCode", false))
        );
        String pseudoPatch = String.valueOf(
                change.getOrDefault("pseudoPatch", "")
        );
        return !writesCode && pseudoPatch.isBlank();
    }

    private String demoStatus(
            List<String> blockers,
            List<GuardedFixDemoStageResponse> stages
    ) {
        if (blockers != null && !blockers.isEmpty()) {
            return "BLOCKED";
        }
        boolean partial = stages.stream()
                .anyMatch(stage -> stage.status().contains("UNAVAILABLE"));
        return partial ? "PARTIAL" : "READY_FOR_REVIEW";
    }

    private String recommendedNextAction(
            String recommendedPath,
            List<String> blockers
    ) {
        if (blockers != null && !blockers.isEmpty()) {
            return "Resolve blockers before executing any real write, push, or PR action.";
        }
        if ("TEST_ONLY_PR".equals(recommendedPath)) {
            return "Use the test-only PR execute endpoint only after explicit approval and real action config are enabled.";
        }
        if ("SOURCE_FIX_PR".equals(recommendedPath)) {
            return "Source fix path still requires explicit source patch approval before writing code.";
        }
        return "Review preview stages and collect missing evidence.";
    }

    private GuardedFixDemoStageResponse stage(
            String name,
            String status,
            String summary,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new GuardedFixDemoStageResponse(
                name,
                firstNonBlank(status, "UNKNOWN"),
                sanitize(summary),
                unique(blockers),
                unique(warnings),
                unique(nextActions)
        );
    }

    private GuardedFixDemoStageResponse unavailableStage(String name) {
        return stage(
                name,
                "UNAVAILABLE",
                "Stage unavailable from existing evidence/services.",
                List.of(name + "_UNAVAILABLE"),
                List.of(),
                List.of("Collect required evidence for " + name + ".")
        );
    }

    private BitbucketWorkspacePushRequest workspacePushRequest(
            GuardedFixDemoPreviewRequest request,
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketWorkspacePushRequest(
                request.requestedBy(),
                request.projectKey(),
                request.repositorySlug(),
                request.jiraKey(),
                "",
                "",
                request.developmentBaseBranch(),
                request.environmentTargetBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                "ReplayFix: " + request.jiraKey()
                        + " guarded draft fix",
                confirm,
                guardrails
        );
    }

    private BitbucketPullRequestRequest pullRequestRequest(
            GuardedFixDemoPreviewRequest request,
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketPullRequestRequest(
                request.requestedBy(),
                request.projectKey(),
                request.repositorySlug(),
                request.integrationBranch(),
                request.targetPrBranch(),
                "[DRAFT] ReplayFix",
                true,
                true,
                true,
                List.of(),
                confirm,
                guardrails
        );
    }

    private List<String> branchBlockers(
            String bugfixBranch,
            String integrationBranch
    ) {
        List<String> blockers = new ArrayList<>();
        if (properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(branch -> branch.equalsIgnoreCase(bugfixBranch)
                        || branch.equalsIgnoreCase(integrationBranch))) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (!bugfixBranch.startsWith(
                properties.getRealActions().getBugfixBranchPrefix())) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID");
        }
        if (!integrationBranch.startsWith(
                properties.getRealActions().getIntegrationBranchPrefix())) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        return unique(blockers);
    }

    private Path workspaceRoot(
            ReplayCaseEntity replayCase,
            String repositorySlug
    ) {
        return repositoryRoot.resolve("work")
                .resolve(replayCase.getId().toString())
                .resolve("repositories")
                .resolve(firstNonBlank(repositorySlug, "backend"))
                .normalize();
    }

    private String generatedTestRelativePath(String jiraKey) {
        return "ControllerBackend/src/test/java/com/company/replayfix/generated/"
                + jiraCompact(jiraKey)
                + "ReplayFixDemoRegressionTest.java";
    }

    private String generatedTestContent(String jiraKey) {
        String safeJira = firstNonBlank(jiraKey, "UNKNOWN");
        String className = jiraCompact(safeJira)
                + "ReplayFixDemoRegressionTest";
        return """
                package com.company.replayfix.generated;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class %s {

                    @Test
                    @Disabled("ReplayFix generated demo regression placeholder; requires domain-specific assertions before enabling.")
                    void replayfix_generated_regression_scenario_for_%s() {
                        assertTrue(true, "ReplayFix demo placeholder for %s");
                    }
                }
                """.formatted(
                className,
                jiraCompact(safeJira),
                safeJira
        );
    }

    private String prDescription(
            ReplayCaseEntity replayCase,
            GuardedFixDemoTestOnlyPrRequest request,
            String generatedPath
    ) {
        return sanitize(String.join(
                "\n",
                "ReplayFix Draft PR",
                "",
                "Source Jira:",
                "- " + request.jiraKey(),
                "",
                "ReplayFix Case:",
                "- " + replayCase.getId(),
                "",
                "Generated file:",
                "- " + generatedPath,
                "",
                "Guardrails:",
                "- Test-only change",
                "- Human review required",
                "- No production source modification",
                "- No auto-merge",
                "- No auto-deploy",
                "- No Jenkins trigger"
        ));
    }

    private GuardedFixDemoTestOnlyPrResponse testOnlyResponse(
            ReplayCaseEntity replayCase,
            GuardedFixDemoTestOnlyPrRequest request,
            String generatedFilePath,
            String commitSha,
            String prUrl,
            String prId,
            boolean executed,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new GuardedFixDemoTestOnlyPrResponse(
                replayCase.getId(),
                request.jiraKey(),
                executed,
                true,
                generatedFilePath,
                request.bugfixBranch(),
                request.integrationBranch(),
                commitSha,
                prUrl,
                prId,
                unique(blockers),
                unique(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private GuardedFixDemoPreviewRequest safePreviewRequest(
            ReplayCaseEntity replayCase,
            GuardedFixDemoPreviewRequest request
    ) {
        GuardedFixDemoPreviewRequest safe = request == null
                ? new GuardedFixDemoPreviewRequest(
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                )
                : request;
        String jiraKey = firstNonBlank(safe.jiraKey(), replayCase.getJiraKey());
        return new GuardedFixDemoPreviewRequest(
                safe.requestedBy(),
                jiraKey,
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                firstNonBlank(
                        safe.developmentBaseBranch(),
                        properties.getRealActions().getDefaultDevelopmentBaseBranch()
                ),
                firstNonBlank(
                        safe.environmentTargetBranch(),
                        properties.getRealActions().getDefaultEnvironmentTargetBranch()
                ),
                firstNonBlank(
                        safe.bugfixBranch(),
                        properties.getRealActions().getBugfixBranchPrefix()
                                + jiraKey
                ),
                firstNonBlank(
                        safe.integrationBranch(),
                        properties.getRealActions().getIntegrationBranchPrefix()
                                + jiraKey
                ),
                firstNonBlank(
                        safe.targetPrBranch(),
                        properties.getRealActions().getDefaultEnvironmentTargetBranch()
                ),
                enabled(safe.preferTestOnlyPrWhenSourceFixNotApproved()),
                enabled(safe.includeCodeAdvisory()),
                enabled(safe.includePatchPlan()),
                enabled(safe.includeRegressionTestDraft()),
                enabled(safe.includeApprovedWritePlan()),
                enabled(safe.includeWorkspaceWrite()),
                enabled(safe.includeWorkspacePush()),
                enabled(safe.includePrPreview())
        );
    }

    private GuardedFixDemoTestOnlyPrRequest safeExecuteRequest(
            ReplayCaseEntity replayCase,
            GuardedFixDemoTestOnlyPrRequest request
    ) {
        GuardedFixDemoTestOnlyPrRequest safe = request == null
                ? new GuardedFixDemoTestOnlyPrRequest(
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        true,
                        false,
                        false
                )
                : request;
        String jiraKey = firstNonBlank(safe.jiraKey(), replayCase.getJiraKey());
        return new GuardedFixDemoTestOnlyPrRequest(
                safe.requestedBy(),
                jiraKey,
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                firstNonBlank(
                        safe.developmentBaseBranch(),
                        properties.getRealActions().getDefaultDevelopmentBaseBranch()
                ),
                firstNonBlank(
                        safe.environmentTargetBranch(),
                        properties.getRealActions().getDefaultEnvironmentTargetBranch()
                ),
                firstNonBlank(
                        safe.bugfixBranch(),
                        properties.getRealActions().getBugfixBranchPrefix()
                                + jiraKey
                ),
                firstNonBlank(
                        safe.integrationBranch(),
                        properties.getRealActions().getIntegrationBranchPrefix()
                                + jiraKey
                ),
                firstNonBlank(
                        safe.targetPrBranch(),
                        properties.getRealActions().getDefaultEnvironmentTargetBranch()
                ),
                firstNonBlank(
                        safe.commitMessage(),
                        "ReplayFix: " + jiraKey + " demo regression test"
                ),
                safe.testOnly(),
                safe.confirmExecute(),
                safe.guardrailsAccepted()
        );
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private void saveEvidence(UUID caseId, Object response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.PULL_REQUEST);
        entity.setSource(SOURCE);
        entity.setSanitized(true);
        entity.setContentText(toJson(response));
        evidenceRepository.save(entity);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        for (String marker : FORBIDDEN_MARKERS) {
            sanitized = sanitized.replaceAll(
                    "(?i)" + marker,
                    "[redacted]"
            );
        }
        return sanitized;
    }

    private String jiraCompact(String jiraKey) {
        return firstNonBlank(jiraKey, "case")
                .replaceAll("[^A-Za-z0-9]", "");
    }

    private List<String> nonBlank(String value) {
        return isBlank(value) ? List.of() : List.of(value);
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return unique(values);
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null ? List.of() : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()
        ));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean enabled(Boolean value) {
        return value == null || value;
    }
}

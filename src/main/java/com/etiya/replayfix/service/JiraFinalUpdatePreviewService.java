package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.JiraFinalUpdateCommentSection;
import com.etiya.replayfix.api.dto.JiraFinalUpdatePreviewResponse;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JiraFinalUpdatePreviewService {

    private final ReplayCaseRepository caseRepository;
    private final PatchPlanCandidateService patchPlanCandidateService;
    private final GeneratedDraftReviewService generatedDraftReviewService;
    private final TestExecutionPlanService testExecutionPlanService;

    public JiraFinalUpdatePreviewService(
            ReplayCaseRepository caseRepository,
            PatchPlanCandidateService patchPlanCandidateService,
            GeneratedDraftReviewService generatedDraftReviewService,
            TestExecutionPlanService testExecutionPlanService
    ) {
        this.caseRepository = caseRepository;
        this.patchPlanCandidateService = patchPlanCandidateService;
        this.generatedDraftReviewService = generatedDraftReviewService;
        this.testExecutionPlanService = testExecutionPlanService;
    }

    @Transactional(readOnly = true)
    public JiraFinalUpdatePreviewResponse preview(
            UUID caseId,
            boolean includeRca,
            boolean includeTestPlan,
            boolean includePatchPlan,
            boolean includeReplayStatus
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        PatchPlanCandidateResponse patchPlan =
                patchPlanCandidateService.candidate(
                        caseId,
                        false,
                        includeReplayStatus,
                        true
                );
        List<String> testCommands = includeTestPlan
                ? testCommands(patchPlan)
                : List.of();

        List<JiraFinalUpdateCommentSection> sections = new ArrayList<>();
        sections.add(section(
                "Summary",
                "ReplayFix generated a hypothesis-only final update preview. No production change, PR, Jenkins run, or ArgoCD action was performed.",
                List.of(
                        "Case: " + replayCase.getJiraKey(),
                        "Target: " + replayCase.getTargetKey(),
                        "Status: HYPOTHESIS"
                )
        ));
        sections.add(section(
                "Evidence",
                "Evidence remains incomplete and must be reviewed by a human.",
                patchPlan.missingEvidence()
        ));
        if (includeRca) {
            sections.add(section(
                    "Source reasoning",
                    "Source reasoning is advisory only. The defect is not confirmed until replay reproduction exists.",
                    List.of(
                            "Target endpoint: " + patchPlan.targetEndpoint(),
                            "Target class: " + patchPlan.targetClass(),
                            "Target method: " + patchPlan.targetMethod()
                    )
            ));
        }
        sections.add(section(
                "Regression test draft",
                "Regression and fix drafts are handled by the generated-draft-review endpoint. This Jira preview does not inspect or publish file content.",
                List.of(
                        "Review status: SEE_GENERATED_DRAFT_REVIEW_ENDPOINT",
                        "shouldRunTests=false",
                        "requiresHumanApproval=true"
                )
        ));
        if (includeReplayStatus) {
            sections.add(section(
                    "Replay environment readiness",
                    "Replay provisioning remains approval-gated.",
                    replayStatusBullets(patchPlan.replayReadiness())
            ));
        }
        if (includePatchPlan) {
            sections.add(section(
                    "Patch plan candidate",
                    "Patch plan remains a hypothesis and is not authorized for source writes.",
                    List.of(
                            "Patch plan status: " + patchPlan.patchPlanStatus(),
                            "Recommended change type: " + patchPlan.recommendedChangeType(),
                            "shouldProceedToPatch=false"
                    )
            ));
            sections.add(section(
                    "Approved write plan",
                    "Only workspace draft files are allowed before separate approval.",
                    List.of(
                            "requiresHumanApproval=true",
                            "No direct test2 commit",
                            "No PR"
                    )
            ));
        }
        sections.add(section(
                "Workspace write status",
                "Workspace draft files may exist under ReplayFix work directory only.",
                List.of("Workspace: " + workspacePath(replayCase.getId()))
        ));
        sections.add(section(
                "Generated draft review",
                "Generated draft review remains read-only.",
                List.of(
                        "Review endpoint: /api/v1/cases/" + replayCase.getId()
                                + "/generated-draft-review",
                        "shouldRunTests=false",
                        "requiresHumanApproval=true"
                )
        ));
        if (includeTestPlan) {
            sections.add(section(
                    "Test execution plan",
                    "Test execution is not enabled by this preview.",
                    List.of(
                            "Execution plan status: BLOCKED_BY_MISSING_APPROVAL",
                            "shouldRunTests=false",
                            "Commands: " + String.join(" | ", testCommands)
                    )
            ));
        }
        sections.add(section(
                "Missing evidence",
                "The following evidence is still required before any patch or validation can proceed.",
                patchPlan.missingEvidence()
        ));
        sections.add(section(
                "Next action",
                "Review the generated drafts and decide whether to approve a separate test execution plan.",
                List.of(
                        "Confirm replay reproduction",
                        "Confirm DB evidence",
                        "Approve test execution separately"
                )
        ));
        sections.add(section(
                "Human approval required",
                "ReplayFix will not publish Jira comments, run tests, create branches, open PRs, or trigger Jenkins from this preview.",
                List.of("requiresHumanApproval=true", "shouldPublish=false")
        ));

        return new JiraFinalUpdatePreviewResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                "HYPOTHESIS",
                true,
                false,
                true,
                sections,
                unique(patchPlan.missingEvidence()),
                unique(allWarnings(patchPlan, null)),
                Instant.now()
        );
    }

    private JiraFinalUpdateCommentSection section(
            String title,
            String body,
            List<String> bullets
    ) {
        return new JiraFinalUpdateCommentSection(title, body, safeBullets(bullets));
    }

    private List<String> replayStatusBullets(Map<String, Object> readiness) {
        if (readiness == null || readiness.isEmpty()) {
            return List.of("Readiness: UNKNOWN");
        }
        return List.of(
                "Readiness status: " + readiness.getOrDefault("readinessStatus", "UNKNOWN"),
                "Request approved: " + readiness.getOrDefault("requestApproved", false),
                "Real provisioning enabled: " + readiness.getOrDefault("realProvisioningEnabled", false)
        );
    }

    private List<String> allWarnings(
            PatchPlanCandidateResponse patchPlan,
            TestExecutionPlanResponse testPlan
    ) {
        List<String> values = new ArrayList<>();
        values.addAll(patchPlan.warnings());
        if (testPlan != null) {
            values.addAll(testPlan.warnings());
        }
        return values;
    }

    private String workspacePath(UUID caseId) {
        return "work/" + caseId + "/repositories/backend";
    }

    private List<String> testCommands(PatchPlanCandidateResponse patchPlan) {
        List<String> commands = new ArrayList<>();
        commands.add("mvn clean compile -DskipTests");
        commands.add("mvn test -Dtest=" + testTarget(patchPlan));
        return List.copyOf(commands);
    }

    private String testTarget(PatchPlanCandidateResponse patchPlan) {
        return firstNonBlank(patchPlan.jiraKey(), "case")
                .replaceAll("[^A-Za-z0-9]", "")
                + capitalize(firstNonBlank(patchPlan.targetMethod(), "Replay"))
                + "RegressionTest";
    }

    private List<String> safeBullets(List<String> bullets) {
        return bullets == null
                ? List.of()
                : bullets.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !containsSensitiveMarker(value))
                .toList();
    }

    private boolean containsSensitiveMarker(String value) {
        String lower = value.toLowerCase();
        return lower.contains("reasoning_content")
                || lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("rawproductionpayload")
                || lower.contains("raw prompt");
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase()
                + value.substring(1);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .filter(value -> !containsSensitiveMarker(value))
                        .toList()
        ));
    }
}

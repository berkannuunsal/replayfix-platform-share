package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replayfix.api.dto.GeneratedDraftReviewedFile;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestExecutionPlanService {

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "([A-Za-z0-9_]+RegressionTest)\\.java$"
    );
    private static final List<String> GUARDRAILS = List.of(
            "NO_AUTO_TEST_EXECUTION",
            "HUMAN_APPROVAL_REQUIRED",
            "WORKSPACE_ONLY",
            "NO_JENKINS",
            "NO_PR",
            "NO_BRANCH",
            "NO_ARGOCD_SYNC",
            "NO_SECRET_EXPOSURE"
    );

    private final GeneratedDraftReviewService generatedDraftReviewService;
    private final PatchPlanCandidateService patchPlanCandidateService;

    public TestExecutionPlanService(
            GeneratedDraftReviewService generatedDraftReviewService,
            PatchPlanCandidateService patchPlanCandidateService
    ) {
        this.generatedDraftReviewService = generatedDraftReviewService;
        this.patchPlanCandidateService = patchPlanCandidateService;
    }

    @Transactional(readOnly = true)
    public TestExecutionPlanResponse plan(
            UUID caseId,
            boolean includeWorkspaceDrafts,
            boolean includeReplayReadiness,
            boolean dryRun
    ) {
        PatchPlanCandidateResponse patchPlan =
                patchPlanCandidateService.candidate(
                        caseId,
                        false,
                        includeReplayReadiness,
                        true
                );
        GeneratedDraftReviewResponse draftReview = includeWorkspaceDrafts
                ? generatedDraftReviewService.review(caseId, false, 0)
                : null;
        List<String> warnings = new ArrayList<>(patchPlan.warnings());
        if (!dryRun) {
            warnings.add("TEST_EXECUTION_PLAN_FORCES_DRY_RUN");
        }
        if (!includeWorkspaceDrafts) {
            warnings.add("WORKSPACE_DRAFT_REVIEW_NOT_INCLUDED");
        }

        List<String> testTargets = testTargets(patchPlan, draftReview);
        List<String> commands = testCommands(testTargets);
        List<String> blockers = blockers(patchPlan, draftReview);

        return new TestExecutionPlanResponse(
                patchPlan.caseId(),
                patchPlan.jiraKey(),
                "HYPOTHESIS",
                "BLOCKED_BY_MISSING_APPROVAL",
                true,
                true,
                false,
                draftReview == null
                        ? workspacePath(patchPlan.caseId())
                        : draftReview.workspacePath(),
                commands,
                testTargets,
                List.of("local", "test2-replay"),
                List.of("WireMock replay mocks"),
                patchPlan.dbValidationRequirements(),
                blockers,
                GUARDRAILS,
                unique(warnings),
                Instant.now()
        );
    }

    private List<String> testTargets(
            PatchPlanCandidateResponse patchPlan,
            GeneratedDraftReviewResponse draftReview
    ) {
        List<String> values = new ArrayList<>();
        if (draftReview != null) {
            for (GeneratedDraftReviewedFile file : draftReview.reviewedFiles()) {
                if (!"REGRESSION_TEST".equals(file.fileType())) {
                    continue;
                }
                String className = classNameFromPath(file.relativePath());
                if (!isBlank(className)) {
                    values.add(className);
                }
            }
        }
        if (values.isEmpty()) {
            values.add(jiraCompact(patchPlan.jiraKey())
                    + capitalize(firstNonBlank(patchPlan.targetMethod(), "Replay"))
                    + "RegressionTest");
        }
        return unique(values);
    }

    private List<String> testCommands(List<String> testTargets) {
        List<String> commands = new ArrayList<>();
        commands.add("mvn clean compile -DskipTests");
        for (String testTarget : testTargets) {
            commands.add("mvn test -Dtest=" + testTarget);
        }
        return List.copyOf(commands);
    }

    private List<String> blockers(
            PatchPlanCandidateResponse patchPlan,
            GeneratedDraftReviewResponse draftReview
    ) {
        List<String> values = new ArrayList<>();
        values.add("HUMAN_APPROVAL_REQUIRED");
        for (String missing : patchPlan.missingEvidence()) {
            switch (missing) {
                case PatchPlanCandidateService.REPLAY_REPRODUCTION ->
                        values.add("REPLAY_REPRODUCTION_MISSING");
                case PatchPlanCandidateService.APPLICATION_DB_EVIDENCE ->
                        values.add("DB_EVIDENCE_MISSING");
                case PatchPlanCandidateService.JENKINS_VALIDATION ->
                        values.add("JENKINS_VALIDATION_NOT_RUN");
                default -> {
                }
            }
        }
        if (draftReview == null) {
            values.add("GENERATED_DRAFT_REVIEW_NOT_INCLUDED");
        } else if (!"READY_FOR_HUMAN_REVIEW".equals(draftReview.reviewStatus())) {
            values.add("GENERATED_DRAFT_REVIEW_NOT_READY");
        }
        return unique(values);
    }

    private String classNameFromPath(String relativePath) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(
                relativePath == null ? "" : relativePath
        );
        return matcher.find() ? matcher.group(1) : "";
    }

    private String workspacePath(UUID caseId) {
        return "work/" + caseId + "/repositories/backend";
    }

    private String jiraCompact(String jiraKey) {
        return firstNonBlank(jiraKey, "case")
                .replaceAll("[^A-Za-z0-9]", "");
    }

    private String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1);
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ApprovedWritePlanFile;
import com.etiya.replayfix.api.dto.ApprovedWritePlanResponse;
import com.etiya.replayfix.api.dto.WorkspaceWriteApprovalRequest;
import com.etiya.replayfix.api.dto.WorkspaceWriteFileResult;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkspaceWriteService {

    private static final Logger log = LoggerFactory.getLogger(
            WorkspaceWriteService.class
    );
    private static final List<String> GUARDRAILS = List.of(
            "WORKSPACE_ONLY_WRITE",
            "HUMAN_APPROVAL_REQUIRED",
            "NO_DIRECT_TEST2_COMMIT",
            "NO_AUTO_PR",
            "NO_AUTO_JENKINS",
            "NO_ARGOCD_SYNC",
            "NO_SECRET_EXPOSURE",
            "NO_RAW_PROD_PAYLOAD",
            "PATH_TRAVERSAL_PROTECTION",
            "DRY_RUN_DEFAULT"
    );

    private final ApprovedWritePlanService approvedWritePlanService;
    private final Path repositoryRoot;

    @Autowired
    public WorkspaceWriteService(
            ApprovedWritePlanService approvedWritePlanService
    ) {
        this(approvedWritePlanService, Path.of("").toAbsolutePath());
    }

    WorkspaceWriteService(
            ApprovedWritePlanService approvedWritePlanService,
            Path repositoryRoot
    ) {
        this.approvedWritePlanService = approvedWritePlanService;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public WorkspaceWriteResponse preview(
            UUID caseId,
            boolean dryRun,
            boolean includeTestDraft,
            boolean includeFixDraft
    ) {
        ApprovedWritePlanResponse plan = approvedWritePlanService.plan(
                caseId,
                null,
                includeTestDraft,
                includeFixDraft,
                true
        );
        List<GeneratedDraft> drafts = generatedDrafts(
                plan,
                includeTestDraft,
                includeFixDraft
        );
        List<String> warnings = new ArrayList<>(plan.warnings());
        if (!dryRun) {
            warnings.add("PREVIEW_ENDPOINT_FORCES_DRY_RUN");
        }
        return response(
                plan,
                "PREVIEW_READY",
                true,
                false,
                List.of(),
                fileResults(drafts, false, "DRAFT"),
                warnings
        );
    }

    public WorkspaceWriteResponse apply(
            UUID caseId,
            WorkspaceWriteApprovalRequest approval,
            boolean dryRun,
            boolean includeTestDraft,
            boolean includeFixDraft
    ) {
        ApprovedWritePlanResponse plan = approvedWritePlanService.plan(
                caseId,
                null,
                includeTestDraft,
                includeFixDraft,
                true
        );
        List<GeneratedDraft> drafts = generatedDrafts(
                plan,
                includeTestDraft,
                includeFixDraft
        );
        boolean approvalPresent = approvalPresent(approval);
        List<String> warnings = new ArrayList<>(plan.warnings());

        if (dryRun) {
            warnings.add("DRY_RUN_TRUE_NO_FILES_WRITTEN");
            return response(
                    plan,
                    "BLOCKED_BY_DRY_RUN",
                    true,
                    approvalPresent,
                    List.of(),
                    fileResults(drafts, false, "DRAFT"),
                    warnings
            );
        }
        if (!approvalPresent) {
            warnings.add("APPROVAL_REQUIRED_BEFORE_WORKSPACE_WRITE");
            return response(
                    plan,
                    "BLOCKED_BY_MISSING_APPROVAL",
                    false,
                    false,
                    List.of(),
                    fileResults(drafts, false, "DRAFT"),
                    warnings
            );
        }

        Path workspaceRoot = workspaceRoot(plan);
        List<String> pathWarnings = validateAllPaths(workspaceRoot, drafts);
        if (!pathWarnings.isEmpty()) {
            warnings.addAll(pathWarnings);
            return response(
                    plan,
                    "BLOCKED_BY_PATH_GUARD",
                    false,
                    true,
                    List.of(),
                    fileResults(drafts, false, "BLOCKED"),
                    warnings
            );
        }

        List<WorkspaceWriteFileResult> written = new ArrayList<>();
        for (GeneratedDraft draft : drafts) {
            Path target = workspaceRoot.resolve(draft.relativePath())
                    .normalize();
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(
                        target,
                        draft.content(),
                        StandardCharsets.UTF_8
                );
                written.add(draft.toResult(true, "WRITTEN"));
            } catch (IOException exception) {
                warnings.add("WORKSPACE_WRITE_FAILED:" + draft.relativePath());
                log.warn(
                        "Workspace write failed caseId={} relativePath={}",
                        plan.caseId(),
                        draft.relativePath(),
                        exception
                );
                return response(
                        plan,
                        "FAILED",
                        false,
                        true,
                        List.copyOf(written),
                        fileResults(drafts, true, "DRAFT"),
                        warnings
                );
            }
        }

        log.info(
                "WORKSPACE_WRITE_COMPLETED caseId={} jiraKey={} filesWritten={}",
                plan.caseId(),
                plan.jiraKey(),
                written.size()
        );
        return response(
                plan,
                "WRITTEN_TO_WORKSPACE",
                false,
                true,
                written,
                fileResults(drafts, true, "DRAFT"),
                warnings
        );
    }

    private WorkspaceWriteResponse response(
            ApprovedWritePlanResponse plan,
            String writeStatus,
            boolean dryRun,
            boolean approvalPresent,
            List<WorkspaceWriteFileResult> written,
            List<WorkspaceWriteFileResult> planned,
            List<String> warnings
    ) {
        return new WorkspaceWriteResponse(
                plan.caseId(),
                plan.jiraKey(),
                "HYPOTHESIS",
                writeStatus,
                dryRun,
                written,
                planned,
                plan.workspacePath(),
                true,
                approvalPresent,
                GUARDRAILS,
                unique(warnings),
                Instant.now()
        );
    }

    private List<GeneratedDraft> generatedDrafts(
            ApprovedWritePlanResponse plan,
            boolean includeTestDraft,
            boolean includeFixDraft
    ) {
        List<GeneratedDraft> drafts = new ArrayList<>();
        if (includeTestDraft) {
            String relativePath = relativePathOverride(plan, "REGRESSION_TEST");
            if (isBlank(relativePath)) {
                relativePath = "src/test/java/com/company/commerce/backend/crm/user/"
                        + jiraCompact(plan.jiraKey())
                        + capitalize(firstNonBlank(targetMethod(plan), "Replay"))
                        + "RegressionTest.java";
            }
            drafts.add(new GeneratedDraft(
                    "REGRESSION_TEST",
                    relativePath,
                    regressionTestContent(plan)
            ));
        }
        if (includeFixDraft) {
            String relativePath = relativePathOverride(plan, "SOURCE_FIX");
            if (isBlank(relativePath)) {
                relativePath = ".replayfix/drafts/"
                        + firstNonBlank(plan.jiraKey(), "case")
                        + "/"
                        + firstNonBlank(targetClass(plan), "Target")
                        + "_"
                        + firstNonBlank(targetMethod(plan), "method")
                        + "_FIX_PLAN.md";
            }
            drafts.add(new GeneratedDraft(
                    "SOURCE_FIX",
                    relativePath,
                    fixPlanMarkdownContent(plan)
            ));
        }
        return List.copyOf(drafts);
    }

    private String regressionTestContent(ApprovedWritePlanResponse plan) {
        String className = jiraCompact(plan.jiraKey())
                + capitalize(firstNonBlank(targetMethod(plan), "Replay"))
                + "RegressionTest";
        return """
                package com.company.commerce.backend.crm.user;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                class %s {

                    @Test
                    @Disabled("Draft generated by ReplayFix. Replace TODO data before enabling.")
                    void preferredProvinceRegionMismatchDraft() {
                        // Endpoint: %s
                        // Scenario: preferredProvince / region mismatch
                        // TODO: use sanitized replay input only.
                        // TODO: create synthetic test data; do not use real customer data.
                        // TODO: assert validation or mapping behavior after human review.
                    }
                }
                """.formatted(
                className,
                firstNonBlank(targetEndpoint(plan), "/user/region/update")
        );
    }

    private String fixPlanMarkdownContent(ApprovedWritePlanResponse plan) {
        return """
                # ReplayFix Workspace Draft Fix Plan

                Jira: %s
                Status: HYPOTHESIS
                Target class: %s
                Target method: %s
                Target endpoint: %s
                Recommended change type: %s

                This file is a workspace-only draft note. It is not a source patch.

                Missing evidence:
                %s

                Human approval is required before any source write, branch creation,
                pull request, Jenkins validation, or ArgoCD action.

                Guardrails:
                - WORKSPACE_ONLY_WRITE
                - NO_DIRECT_TEST2_COMMIT
                - NO_AUTO_PR
                - NO_AUTO_JENKINS
                - NO_ARGOCD_SYNC
                - NO_SECRET_EXPOSURE
                - NO_RAW_PROD_PAYLOAD
                """.formatted(
                firstNonBlank(plan.jiraKey(), "UNKNOWN"),
                firstNonBlank(targetClass(plan), "UNKNOWN"),
                firstNonBlank(targetMethod(plan), "UNKNOWN"),
                firstNonBlank(targetEndpoint(plan), "UNKNOWN"),
                recommendedChangeType(plan),
                missingEvidenceMarkdown(plan)
        );
    }

    private String missingEvidenceMarkdown(ApprovedWritePlanResponse plan) {
        if (plan.missingEvidence().isEmpty()) {
            return "- UNKNOWN";
        }
        return "- " + String.join("\n- ", plan.missingEvidence());
    }

    private List<WorkspaceWriteFileResult> fileResults(
            List<GeneratedDraft> drafts,
            boolean writeAllowed,
            String status
    ) {
        return drafts.stream()
                .map(draft -> draft.toResult(writeAllowed, status))
                .toList();
    }

    private List<String> validateAllPaths(
            Path workspaceRoot,
            List<GeneratedDraft> drafts
    ) {
        List<String> warnings = new ArrayList<>();
        for (GeneratedDraft draft : drafts) {
            warnings.addAll(validatePath(workspaceRoot, draft.relativePath()));
        }
        if (!workspaceRoot.startsWith(repositoryRoot.resolve("work").normalize())) {
            warnings.add("WORKSPACE_PATH_OUTSIDE_REPLAYFIX_WORK");
        }
        return unique(warnings);
    }

    private List<String> validatePath(Path workspaceRoot, String relativePath) {
        List<String> warnings = new ArrayList<>();
        if (isBlank(relativePath)) {
            warnings.add("WORKSPACE_FILE_PATH_MISSING");
            return warnings;
        }
        if (relativePath.contains("..")) {
            warnings.add("PATH_TRAVERSAL_REJECTED:" + relativePath);
        }
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute()) {
                warnings.add("ABSOLUTE_PATH_REJECTED:" + relativePath);
            }
            Path target = workspaceRoot.resolve(relative).normalize();
            if (!target.startsWith(workspaceRoot)) {
                warnings.add("PATH_OUTSIDE_WORKSPACE_REJECTED:" + relativePath);
            }
        } catch (InvalidPathException exception) {
            warnings.add("INVALID_PATH_REJECTED:" + relativePath);
        }
        return warnings;
    }

    private Path workspaceRoot(ApprovedWritePlanResponse plan) {
        return repositoryRoot.resolve(plan.workspacePath()).normalize();
    }

    private boolean approvalPresent(WorkspaceWriteApprovalRequest approval) {
        return approval != null
                && approval.acceptedGuardrails()
                && !isBlank(approval.approvedBy());
    }

    private String relativePathOverride(
            ApprovedWritePlanResponse plan,
            String fileType
    ) {
        for (ApprovedWritePlanFile file : plan.plannedFiles()) {
            if (!fileType.equals(file.fileType())) {
                continue;
            }
            String value = string(file.metadata().get("workspaceWriteRelativePath"));
            if (isBlank(value)) {
                value = string(file.metadata().get("relativePath"));
            }
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String targetClass(ApprovedWritePlanResponse plan) {
        return plan.plannedFiles().stream()
                .map(ApprovedWritePlanFile::targetClass)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse("");
    }

    private String targetMethod(ApprovedWritePlanResponse plan) {
        return plan.plannedFiles().stream()
                .map(ApprovedWritePlanFile::targetMethod)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse("");
    }

    private String targetEndpoint(ApprovedWritePlanResponse plan) {
        return plan.plannedFiles().stream()
                .map(ApprovedWritePlanFile::targetEndpoint)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse("");
    }

    private String recommendedChangeType(ApprovedWritePlanResponse plan) {
        return plan.plannedFiles().stream()
                .map(ApprovedWritePlanFile::recommendedChangeType)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse("UNKNOWN");
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

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record GeneratedDraft(
            String fileType,
            String relativePath,
            String content
    ) {
        WorkspaceWriteFileResult toResult(boolean writeAllowed, String status) {
            return new WorkspaceWriteFileResult(
                    fileType,
                    relativePath,
                    status,
                    writeAllowed,
                    content.length(),
                    content,
                    List.of()
            );
        }
    }
}

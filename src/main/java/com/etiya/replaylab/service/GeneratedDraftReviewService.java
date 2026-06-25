package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ApprovedWritePlanFile;
import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replaylab.api.dto.GeneratedDraftReviewedFile;
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
public class GeneratedDraftReviewService {

    private static final List<String> SECURITY_MARKERS = List.of(
            "password",
            "token",
            "apikey",
            "authorization",
            "cookie",
            "privatekey",
            "begin private key",
            "reasoning_content",
            "rawproductionpayload",
            "raw prompt",
            "real customer personal data marker"
    );

    private final ApprovedWritePlanService approvedWritePlanService;
    private final Path repositoryRoot;

    @Autowired
    public GeneratedDraftReviewService(
            ApprovedWritePlanService approvedWritePlanService
    ) {
        this(approvedWritePlanService, Path.of("").toAbsolutePath());
    }

    GeneratedDraftReviewService(
            ApprovedWritePlanService approvedWritePlanService,
            Path repositoryRoot
    ) {
        this.approvedWritePlanService = approvedWritePlanService;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public GeneratedDraftReviewResponse review(
            UUID caseId,
            boolean includeFileContentPreview,
            int maxPreviewChars
    ) {
        ApprovedWritePlanResponse plan = approvedWritePlanService.plan(
                caseId,
                null,
                true,
                true,
                true
        );
        int previewLimit = Math.max(0, Math.min(maxPreviewChars, 1200));
        Path workspaceRoot = repositoryRoot.resolve(plan.workspacePath())
                .normalize();
        List<ExpectedDraftFile> expectedFiles = expectedFiles(plan);

        List<GeneratedDraftReviewedFile> reviewedFiles = new ArrayList<>();
        List<String> securityFindings = new ArrayList<>();
        List<String> qualityFindings = new ArrayList<>();
        List<String> missingItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>(plan.warnings());

        if (!workspaceRoot.startsWith(repositoryRoot.resolve("work").normalize())) {
            securityFindings.add("WORKSPACE_PATH_OUTSIDE_REPLAYLAB_WORK");
        }

        for (ExpectedDraftFile expectedFile : expectedFiles) {
            ReviewedDraft reviewed = reviewFile(
                    workspaceRoot,
                    expectedFile,
                    plan,
                    includeFileContentPreview,
                    previewLimit
            );
            reviewedFiles.add(reviewed.file());
            securityFindings.addAll(reviewed.securityFindings());
            qualityFindings.addAll(reviewed.qualityFindings());
            missingItems.addAll(reviewed.missingItems());
            warnings.addAll(reviewed.warnings());
        }

        boolean regressionExists = reviewedFiles.stream()
                .anyMatch(file -> "REGRESSION_TEST".equals(file.fileType())
                        && file.exists());
        boolean fixExists = reviewedFiles.stream()
                .anyMatch(file -> "SOURCE_FIX".equals(file.fileType())
                        && file.exists());
        String reviewStatus = reviewStatus(
                reviewedFiles,
                securityFindings,
                regressionExists,
                fixExists
        );

        return new GeneratedDraftReviewResponse(
                plan.caseId(),
                plan.jiraKey(),
                "HYPOTHESIS",
                reviewStatus,
                plan.workspacePath(),
                reviewedFiles,
                unique(securityFindings),
                unique(qualityFindings),
                unique(missingItems),
                nextActions(reviewStatus, qualityFindings, missingItems),
                true,
                false,
                false,
                false,
                unique(warnings),
                Instant.now()
        );
    }

    private ReviewedDraft reviewFile(
            Path workspaceRoot,
            ExpectedDraftFile expectedFile,
            ApprovedWritePlanResponse plan,
            boolean includeFileContentPreview,
            int previewLimit
    ) {
        List<String> securityFindings = new ArrayList<>();
        List<String> qualityFindings = new ArrayList<>();
        List<String> missingItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean pathSafe = true;
        Path relative = null;
        Path filePath = null;

        if (expectedFile.relativePath().contains("..")) {
            securityFindings.add("PATH_TRAVERSAL_REJECTED");
            pathSafe = false;
        }
        try {
            relative = Path.of(expectedFile.relativePath());
            if (relative.isAbsolute()) {
                securityFindings.add("ABSOLUTE_PATH_REJECTED");
                pathSafe = false;
            }
            filePath = workspaceRoot.resolve(relative).normalize();
            if (!filePath.startsWith(workspaceRoot)) {
                securityFindings.add("PATH_OUTSIDE_WORKSPACE_REJECTED");
                pathSafe = false;
            }
        } catch (InvalidPathException exception) {
            securityFindings.add("INVALID_PATH_REJECTED");
            pathSafe = false;
        }

        if (!pathSafe || filePath == null) {
            return reviewed(
                    expectedFile,
                    false,
                    false,
                    0,
                    "",
                    securityFindings,
                    qualityFindings,
                    missingItems,
                    warnings
            );
        }

        if (!Files.exists(filePath)) {
            missingItems.add("MISSING_" + expectedFile.fileType());
            return reviewed(
                    expectedFile,
                    false,
                    true,
                    0,
                    "",
                    securityFindings,
                    qualityFindings,
                    missingItems,
                    warnings
            );
        }

        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            warnings.add("GENERATED_DRAFT_READ_FAILED:" + expectedFile.fileType());
            return reviewed(
                    expectedFile,
                    true,
                    true,
                    0,
                    "",
                    securityFindings,
                    qualityFindings,
                    missingItems,
                    warnings
            );
        }

        List<String> contentSecurityFindings = securityFindings(content);
        securityFindings.addAll(contentSecurityFindings);
        if (contentSecurityFindings.isEmpty()) {
            qualityFindings.addAll(qualityFindings(expectedFile, content, plan));
            missingItems.addAll(missingQualityItems(expectedFile, content, plan));
        }
        String preview = includeFileContentPreview
                && contentSecurityFindings.isEmpty()
                ? capped(content, previewLimit)
                : "";
        return reviewed(
                expectedFile,
                true,
                true,
                content.length(),
                preview,
                securityFindings,
                qualityFindings,
                missingItems,
                warnings
        );
    }

    private ReviewedDraft reviewed(
            ExpectedDraftFile expectedFile,
            boolean exists,
            boolean pathSafe,
            int contentChars,
            String preview,
            List<String> securityFindings,
            List<String> qualityFindings,
            List<String> missingItems,
            List<String> warnings
    ) {
        return new ReviewedDraft(
                new GeneratedDraftReviewedFile(
                        expectedFile.fileType(),
                        expectedFile.relativePath(),
                        exists,
                        pathSafe,
                        contentChars,
                        preview,
                        unique(securityFindings),
                        unique(qualityFindings),
                        unique(warnings)
                ),
                unique(securityFindings),
                unique(qualityFindings),
                unique(missingItems),
                unique(warnings)
        );
    }

    private List<ExpectedDraftFile> expectedFiles(
            ApprovedWritePlanResponse plan
    ) {
        List<ExpectedDraftFile> values = new ArrayList<>();
        values.add(new ExpectedDraftFile(
                "REGRESSION_TEST",
                relativePathOverride(plan, "REGRESSION_TEST",
                        "src/test/java/com/company/commerce/backend/crm/user/"
                                + jiraCompact(plan.jiraKey())
                                + capitalize(firstNonBlank(targetMethod(plan), "Replay"))
                                + "RegressionTest.java")
        ));
        values.add(new ExpectedDraftFile(
                "SOURCE_FIX",
                relativePathOverride(plan, "SOURCE_FIX",
                        ".replaylab/drafts/"
                                + firstNonBlank(plan.jiraKey(), "case")
                                + "/"
                                + firstNonBlank(targetClass(plan), "Target")
                                + "_"
                                + firstNonBlank(targetMethod(plan), "method")
                                + "_FIX_PLAN.md")
        ));
        return List.copyOf(values);
    }

    private String relativePathOverride(
            ApprovedWritePlanResponse plan,
            String fileType,
            String fallback
    ) {
        for (ApprovedWritePlanFile file : plan.plannedFiles()) {
            if (!fileType.equals(file.fileType())) {
                continue;
            }
            String value = string(file.metadata().get("workspaceWriteRelativePath"));
            if (isBlank(value)) {
                value = string(file.metadata().get("relativePath"));
            }
            return isBlank(value) ? fallback : value;
        }
        return fallback;
    }

    private List<String> securityFindings(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        List<String> findings = new ArrayList<>();
        for (String marker : SECURITY_MARKERS) {
            if (normalized.contains(marker)) {
                findings.add("SECURITY_MARKER_FOUND");
            }
        }
        return unique(findings);
    }

    private List<String> qualityFindings(
            ExpectedDraftFile expectedFile,
            String content,
            ApprovedWritePlanResponse plan
    ) {
        List<String> findings = new ArrayList<>();
        for (String item : missingQualityItems(expectedFile, content, plan)) {
            findings.add("QUALITY_ITEM_MISSING:" + item);
        }
        return unique(findings);
    }

    private List<String> missingQualityItems(
            ExpectedDraftFile expectedFile,
            String content,
            ApprovedWritePlanResponse plan
    ) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        if ("REGRESSION_TEST".equals(expectedFile.fileType())) {
            requireContains(content, plan.jiraKey(), "JIRA_REFERENCE", missing);
            requireContains(content, "/user/region/update", "TARGET_ENDPOINT", missing);
            requireContains(content, "preferredProvince", "PREFERRED_PROVINCE_SCENARIO", missing);
            requireContains(content, "region mismatch", "REGION_MISMATCH_SCENARIO", missing);
            if (!lower.contains("todo") && !lower.contains("placeholder")) {
                missing.add("SANITIZED_TEST_DATA_PLACEHOLDER");
            }
            if (!lower.contains("assert")) {
                missing.add("ASSERTION_SECTION");
            }
            if (!lower.contains("draft")) {
                missing.add("DRAFT_NOTE");
            }
        } else if ("SOURCE_FIX".equals(expectedFile.fileType())) {
            requireContains(content, targetClass(plan), "TARGET_CLASS", missing);
            requireContains(content, targetMethod(plan), "TARGET_METHOD", missing);
            requireContains(content, "VALIDATION_GUARD", "RECOMMENDED_CHANGE_TYPE", missing);
            requireContains(content, "Missing evidence", "MISSING_EVIDENCE_LIST", missing);
            requireContains(content, "Human approval", "HUMAN_APPROVAL_NOTE", missing);
            requireContains(content, "NO_DIRECT_TEST2_COMMIT", "NO_DIRECT_TEST2_COMMIT_NOTE", missing);
        }
        return unique(missing);
    }

    private void requireContains(
            String content,
            String expected,
            String missingCode,
            List<String> missing
    ) {
        if (isBlank(expected)) {
            missing.add(missingCode);
            return;
        }
        if (!content.contains(expected)) {
            missing.add(missingCode);
        }
    }

    private String reviewStatus(
            List<GeneratedDraftReviewedFile> reviewedFiles,
            List<String> securityFindings,
            boolean regressionExists,
            boolean fixExists
    ) {
        boolean pathBlocked = reviewedFiles.stream()
                .anyMatch(file -> !file.pathSafe());
        boolean anyExists = reviewedFiles.stream()
                .anyMatch(GeneratedDraftReviewedFile::exists);
        if (pathBlocked || securityFindings.stream()
                .anyMatch(finding -> finding.contains("PATH_")
                        || finding.contains("ABSOLUTE_PATH")
                        || finding.contains("INVALID_PATH"))) {
            return "BLOCKED_BY_PATH_GUARD";
        }
        if (!securityFindings.isEmpty()) {
            return "BLOCKED_BY_SECURITY_FINDINGS";
        }
        if (!anyExists) {
            return "NO_GENERATED_FILES";
        }
        if (!regressionExists) {
            return "BLOCKED_BY_MISSING_TEST_DRAFT";
        }
        if (!fixExists) {
            return "BLOCKED_BY_MISSING_FIX_DRAFT";
        }
        return "READY_FOR_HUMAN_REVIEW";
    }

    private List<String> nextActions(
            String reviewStatus,
            List<String> qualityFindings,
            List<String> missingItems
    ) {
        List<String> values = new ArrayList<>();
        values.add("Human review is required before test execution.");
        values.add("Do not create a branch, commit, PR, Jenkins run, or ArgoCD sync from this review.");
        if (!qualityFindings.isEmpty() || !missingItems.isEmpty()) {
            values.add("Address missing draft review items before enabling tests.");
        }
        if ("READY_FOR_HUMAN_REVIEW".equals(reviewStatus)) {
            values.add("Review generated draft files and approve a separate test execution plan.");
        }
        return List.copyOf(values);
    }

    private String capped(String content, int maxPreviewChars) {
        if (maxPreviewChars <= 0) {
            return "";
        }
        return content.length() <= maxPreviewChars
                ? content
                : content.substring(0, maxPreviewChars);
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

    private record ExpectedDraftFile(String fileType, String relativePath) {
    }

    private record ReviewedDraft(
            GeneratedDraftReviewedFile file,
            List<String> securityFindings,
            List<String> qualityFindings,
            List<String> missingItems,
            List<String> warnings
    ) {
    }
}

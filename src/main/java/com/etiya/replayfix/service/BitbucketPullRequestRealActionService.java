package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BitbucketPullRequestRealActionService {

    private static final String SOURCE = "replayfix-real-action-bitbucket-pr";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final CodeChangeAdvisoryService advisoryService;
    private final PatchPlanCandidateService patchPlanService;
    private final TestExecutionPlanService testExecutionPlanService;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketPullRequestRealActionService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            CodeChangeAdvisoryService advisoryService,
            PatchPlanCandidateService patchPlanService,
            TestExecutionPlanService testExecutionPlanService,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.bitbucketClient = bitbucketClient;
        this.advisoryService = advisoryService;
        this.patchPlanService = patchPlanService;
        this.testExecutionPlanService = testExecutionPlanService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BitbucketPullRequestResponse preview(
            UUID caseId,
            BitbucketPullRequestRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedPullRequest normalized = normalize(replayCase, request);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = warnings(replayCase, request);
        checkBranches(normalized, blockers, warnings);
        return build(
                replayCase,
                normalized,
                request,
                true,
                false,
                "",
                "",
                blockers,
                warnings
        );
    }

    @Transactional
    public BitbucketPullRequestResponse create(
            UUID caseId,
            BitbucketPullRequestRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketPullRequestRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        NormalizedPullRequest normalized = normalize(replayCase, safeRequest);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = warnings(replayCase, safeRequest);
        checkBranches(normalized, blockers, warnings);
        if (!blockers.isEmpty()) {
            return build(
                    replayCase,
                    normalized,
                    safeRequest,
                    false,
                    false,
                    "",
                    "",
                    blockers,
                    warnings
            );
        }

        BitbucketPullRequestResponse preview = build(
                replayCase,
                normalized,
                safeRequest,
                true,
                false,
                "",
                "",
                blockers,
                warnings
        );
        PullRequestResult created = bitbucketClient.createPullRequest(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.sourceBranch(),
                normalized.targetBranch(),
                preview.title(),
                preview.descriptionPreview(),
                safeRequest.reviewerUsers() == null
                        ? List.of()
                        : safeRequest.reviewerUsers()
        );
        BitbucketPullRequestResponse response = build(
                replayCase,
                normalized,
                safeRequest,
                false,
                true,
                nullToBlank(created.url()),
                nullToBlank(created.id()),
                List.of(),
                warnings
        );
        replayCase.setPullRequestUrl(response.pullRequestUrl());
        caseRepository.save(replayCase);
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private void checkBranches(
            NormalizedPullRequest request,
            List<String> blockers,
            List<String> warnings
    ) {
        BitbucketBranchCheckResult source =
                bitbucketClient.branchExists(
                        request.projectKey(),
                        request.repositorySlug(),
                        request.sourceBranch()
                );
        if (!source.exists()) {
            blockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }
        warnings.addAll(source.warnings());
        BitbucketBranchCheckResult target =
                bitbucketClient.branchExists(
                        request.projectKey(),
                        request.repositorySlug(),
                        request.targetBranch()
                );
        if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }
        warnings.addAll(target.warnings());
    }

    private BitbucketPullRequestResponse build(
            ReplayCaseEntity replayCase,
            NormalizedPullRequest request,
            BitbucketPullRequestRequest originalRequest,
            boolean previewOnly,
            boolean created,
            String pullRequestUrl,
            String pullRequestId,
            List<String> blockers,
            List<String> warnings
    ) {
        BitbucketPullRequestRequest safeRequest = safeRequest(originalRequest);
        String title = firstNonBlank(
                safeRequest.titlePrefix(),
                properties.getRealActions().getDraftPrTitlePrefix()
        ) + " " + request.jiraKey() + " replay fix plan";
        if (!title.startsWith("[DRAFT]")) {
            title = "[DRAFT] " + title;
        }
        if (!title.contains("ReplayLab")) {
            title = title.replace("[DRAFT]", "[DRAFT] ReplayLab");
        }
        String description = description(replayCase, request, safeRequest);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("description", description);
        payload.put("sourceBranch", request.sourceBranch());
        payload.put("targetBranch", request.targetBranch());
        payload.put(
                "reviewers",
                safeRequest.reviewerUsers() == null
                        ? List.of()
                        : safeRequest.reviewerUsers()
        );
        return new BitbucketPullRequestResponse(
                replayCase.getId(),
                request.jiraKey(),
                previewOnly,
                created,
                pullRequestUrl,
                pullRequestId,
                request.projectKey(),
                request.repositorySlug(),
                request.sourceBranch(),
                request.targetBranch(),
                title,
                description,
                payload,
                unique(blockers),
                unique(warnings),
                blockers == null || blockers.isEmpty()
                        ? List.of("Create draft PR only after human review.")
                        : List.of("Resolve PR blockers before creation."),
                Instant.now()
        );
    }

    private String description(
            ReplayCaseEntity replayCase,
            NormalizedPullRequest request,
            BitbucketPullRequestRequest flags
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("ReplayLab Draft PR");
        lines.add("");
        lines.add("Source Jira:");
        lines.add("- " + request.jiraKey());
        lines.add("");
        lines.add("ReplayLab Case:");
        lines.add("- " + replayCase.getId());
        lines.add("");
        lines.add("Branch Flow:");
        lines.add("- developmentBaseBranch: "
                + properties.getRealActions().getDefaultDevelopmentBaseBranch());
        lines.add("- bugfixBranch: "
                + properties.getRealActions().getBugfixBranchPrefix()
                + request.jiraKey());
        lines.add("- integrationBranch: " + request.sourceBranch());
        lines.add("- targetBranch: " + request.targetBranch());
        lines.add("");
        if (flags.includeCodeAdvisory()) {
            try {
                CodeChangeAdvisoryEvaluationSummaryResponse advisory =
                        advisoryService.summary(replayCase.getId());
                lines.add("Code Advisory:");
                lines.add("- generated count: "
                        + advisory.advisoryGeneratedCount());
                lines.add("- status: " + advisory.caseAdvisoryStatus());
                lines.add("");
            } catch (Exception exception) {
                lines.add("Code Advisory:");
                lines.add("- unavailable");
                lines.add("");
            }
        }
        if (flags.includePatchPlan()) {
            try {
                PatchPlanCandidateResponse patch =
                        patchPlanService.candidate(
                                replayCase.getId(),
                                false,
                                true,
                                true
                        );
                lines.add("Patch Plan:");
                lines.add("- status: " + patch.patchPlanStatus());
                lines.add("- target: " + patch.targetClass()
                        + "#" + patch.targetMethod());
                lines.add("");
            } catch (Exception exception) {
                lines.add("Patch Plan:");
                lines.add("- unavailable");
                lines.add("");
            }
        }
        if (flags.includeTestPlan()) {
            try {
                TestExecutionPlanResponse plan =
                        testExecutionPlanService.plan(
                                replayCase.getId(),
                                true,
                                true,
                                true
                        );
                lines.add("Test Plan:");
                lines.addAll(plan.testCommands().stream()
                        .map(value -> "- " + value)
                        .toList());
                lines.add("");
            } catch (Exception exception) {
                lines.add("Test Plan:");
                lines.add("- unavailable");
                lines.add("");
            }
        }
        lines.add("Guardrails:");
        lines.add("- Draft PR only");
        lines.add("- Human review required");
        lines.add("- No auto-merge");
        lines.add("- No auto-deploy");
        return sanitize(String.join("\n", lines));
    }

    private List<String> warnings(
            ReplayCaseEntity replayCase,
            BitbucketPullRequestRequest request
    ) {
        List<String> warnings = new ArrayList<>();
        if (isBlank(replayCase.getGeneratedBranch())) {
            warnings.add("NO_APPROVED_WRITE_PLAN_FOUND");
        }
        if (request == null || !request.includePatchPlan()) {
            warnings.add("NO_PATCH_PLAN_FOUND");
        }
        return warnings;
    }

    private void validateCreate(BitbucketPullRequestRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.requestedBy())) {
            errors.add("REQUESTED_BY_REQUIRED");
        }
        if (properties.getRealActions().isRequireConfirmation()
                && !request.confirmCreate()) {
            errors.add("CONFIRM_CREATE_REQUIRED");
        }
        if (properties.getRealActions().isRequireGuardrailsAccepted()
                && !request.guardrailsAccepted()) {
            errors.add("GUARDRAILS_ACCEPTED_REQUIRED");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.join(",", errors)
            );
        }
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isBitbucketPrCreateEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REAL_ACTIONS_DISABLED"
            );
        }
    }

    private NormalizedPullRequest normalize(
            ReplayCaseEntity replayCase,
            BitbucketPullRequestRequest request
    ) {
        BitbucketPullRequestRequest safe = safeRequest(request);
        String jiraKey = firstNonBlank(replayCase.getJiraKey(), "UNKNOWN");
        String targetBranch = firstNonBlank(
                safe.targetBranch(),
                properties.getRealActions().getDefaultEnvironmentTargetBranch()
        );
        return new NormalizedPullRequest(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                jiraKey,
                firstNonBlank(
                        safe.sourceBranch(),
                        properties.getRealActions().getIntegrationBranchPrefix()
                                + jiraKey
                ),
                targetBranch
        );
    }

    private void saveEvidence(UUID caseId, BitbucketPullRequestResponse response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.PULL_REQUEST);
        entity.setSource(SOURCE);
        entity.setSanitized(true);
        entity.setContentText(toJson(response));
        evidenceRepository.save(entity);
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private BitbucketPullRequestRequest safeRequest(
            BitbucketPullRequestRequest request
    ) {
        return request == null
                ? new BitbucketPullRequestRequest(
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        true,
                        true,
                        true,
                        List.of(),
                        false,
                        false
                )
                : request;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null ? List.of() : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()
        ));
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]");
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

    private record NormalizedPullRequest(
            String projectKey,
            String repositorySlug,
            String jiraKey,
            String sourceBranch,
            String targetBranch
    ) {
    }
}

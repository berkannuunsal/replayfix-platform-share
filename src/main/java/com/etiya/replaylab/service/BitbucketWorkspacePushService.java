package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketWorkspacePushRequest;
import com.etiya.replaylab.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replaylab.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BitbucketWorkspacePushService {

    private static final String SOURCE = "replaylab-real-action-bitbucket-workspace-push";
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "password",
            "token",
            "secret",
            "apiKey",
            "privateKey",
            "Authorization",
            "Cookie"
    );
    private static final List<Pattern> FORBIDDEN_PATTERNS =
            FORBIDDEN_MARKERS.stream()
                    .map(marker -> Pattern.compile(
                            "(?i)(^|[^A-Za-z0-9_])"
                                    + Pattern.quote(marker)
                                    + "([^A-Za-z0-9_]|$)"
                    ))
                    .toList();

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final GeneratedDraftReviewService draftReviewService;
    private final WorkspaceGitOperations gitOperations;
    private final ReplayLabProperties properties;
    private final ObjectMapper objectMapper;
    private final Path repositoryRoot;

    @Autowired
    public BitbucketWorkspacePushService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            GeneratedDraftReviewService draftReviewService,
            WorkspaceGitOperations gitOperations,
            ReplayLabProperties properties,
            ObjectMapper objectMapper
    ) {
        this(
                caseRepository,
                evidenceRepository,
                draftReviewService,
                gitOperations,
                properties,
                objectMapper,
                Path.of("").toAbsolutePath()
        );
    }

    BitbucketWorkspacePushService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            GeneratedDraftReviewService draftReviewService,
            WorkspaceGitOperations gitOperations,
            ReplayLabProperties properties,
            ObjectMapper objectMapper,
            Path repositoryRoot
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.draftReviewService = draftReviewService;
        this.gitOperations = gitOperations;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public BitbucketWorkspacePushResponse preview(
            UUID caseId,
            BitbucketWorkspacePushRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedPush normalized = normalize(replayCase, request);
        PlanValidation validation = validatePlan(replayCase, normalized);
        List<String> warnings = new ArrayList<>(validation.warnings());
        warnings.add("PREVIEW_ONLY_NO_GIT_PUSH");
        return response(
                replayCase,
                normalized,
                true,
                false,
                "",
                false,
                false,
                false,
                false,
                false,
                validation.blockers(),
                warnings,
                nextActions(validation.blockers())
        );
    }

    @Transactional
    public BitbucketWorkspacePushResponse execute(
            UUID caseId,
            BitbucketWorkspacePushRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketWorkspacePushRequest safeRequest = safeRequest(request);
        validateExecute(safeRequest);
        NormalizedPush normalized = normalize(replayCase, safeRequest);
        PlanValidation validation = validatePlan(replayCase, normalized);
        if (!validation.blockers().isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    "",
                    false,
                    false,
                    false,
                    false,
                    false,
                    validation.blockers(),
                    validation.warnings(),
                    nextActions(validation.blockers())
            );
        }
        WorkspaceGitOperations.WorkspaceGitPushResult pushed =
                gitOperations.pushApprovedChanges(
                        validation.workspaceRoot(),
                        normalized.developmentBaseBranch(),
                        normalized.environmentTargetBranch(),
                        normalized.bugfixBranch(),
                        normalized.integrationBranch(),
                        normalized.commitMessage()
                );
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(validation.warnings());
        if (!isBlank(pushed.warning())) {
            warnings.add(pushed.warning());
        }
        if (!isBlank(pushed.error())) {
            if (pushed.error().contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN")) {
                blockers.add("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
            } else {
                blockers.add("WORKSPACE_PUSH_FAILED");
            }
        }
        boolean executed = blockers.isEmpty() && pushed.bugfixBranchPushed();
        BitbucketWorkspacePushResponse response = response(
                replayCase,
                normalized,
                false,
                executed,
                pushed.commitSha(),
                pushed.bugfixBranchPushed(),
                pushed.integrationBranchPrepared(),
                pushed.mergeAttempted(),
                pushed.mergeSucceeded(),
                pushed.mergeConflict(),
                blockers,
                warnings,
                nextActions(blockers)
        );
        if (executed) {
            replayCase.setGeneratedBranch(normalized.integrationBranch());
            caseRepository.save(replayCase);
            saveEvidence(replayCase.getId(), response);
        }
        return response;
    }

    private PlanValidation validatePlan(
            ReplayCaseEntity replayCase,
            NormalizedPush request
    ) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (protectedBranch(request.bugfixBranch())
                || protectedBranch(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (!request.bugfixBranch().startsWith(
                properties.getRealActions().getBugfixBranchPrefix())) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID");
        }
        if (!request.integrationBranch().startsWith(
                properties.getRealActions().getIntegrationBranchPrefix())) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        GeneratedDraftReviewResponse review = draftReviewService.review(
                replayCase.getId(),
                false,
                0
        );
        if (isBlank(review.workspacePath())) {
            blockers.add("WORKSPACE_PATH_MISSING");
        }
        if (review.reviewedFiles().isEmpty()) {
            blockers.add("APPROVED_WORKSPACE_WRITE_MISSING");
        }
        if (!"READY_FOR_HUMAN_REVIEW".equals(review.reviewStatus())) {
            blockers.add("WORKSPACE_WRITE_NOT_APPROVED_OR_REVIEWED");
        }
        if (!review.securityFindings().isEmpty()) {
            blockers.add("WORKSPACE_SECURITY_FINDINGS_PRESENT");
        }
        if (!review.qualityFindings().isEmpty()) {
            warnings.add("WORKSPACE_QUALITY_FINDINGS_PRESENT");
        }
        Path workspaceRoot = repositoryRoot.resolve(review.workspacePath())
                .normalize();
        boolean workspacePathAllowed = !isBlank(review.workspacePath())
                && workspaceRoot.startsWith(repositoryRoot.resolve("work").normalize());
        if (!workspacePathAllowed) {
            blockers.add("WORKSPACE_PATH_OUTSIDE_REPLAYLAB_WORK");
        }
        if (workspacePathAllowed) {
            List<String> sensitiveFindings = sensitiveFindings(workspaceRoot);
            if (!sensitiveFindings.isEmpty()) {
                blockers.add("WORKSPACE_DIFF_CONTAINS_FORBIDDEN_SENSITIVE_MARKER");
                warnings.addAll(sensitiveFindings);
            }
            WorkspaceGitOperations.WorkspaceGitStatus status =
                    gitOperations.status(workspaceRoot);
            if (status == null) {
                blockers.add("WORKSPACE_GIT_STATUS_UNAVAILABLE");
            } else if (!status.gitRepository()) {
                blockers.add("WORKSPACE_GIT_REPOSITORY_MISSING");
            } else if (!status.hasChanges()) {
                warnings.add("WORKSPACE_HAS_NO_PENDING_CHANGES");
            }
            if (status != null
                    && !isBlank(status.error())
                    && !"WORKSPACE_GIT_REPOSITORY_MISSING".equals(status.error())) {
                warnings.add(status.error());
            }
        }
        return new PlanValidation(
                unique(blockers),
                unique(warnings),
                workspaceRoot
        );
    }

    private void validateExecute(BitbucketWorkspacePushRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.requestedBy())) {
            errors.add("REQUESTED_BY_REQUIRED");
        }
        if (!request.confirmPush()) {
            errors.add("CONFIRM_PUSH_REQUIRED");
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
                || !properties.getRealActions().isBitbucketBranchCreateEnabled()
                || !properties.getRealActions().isBitbucketPushEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REAL_ACTIONS_DISABLED"
            );
        }
    }

    private List<String> sensitiveFindings(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        try (var stream = Files.walk(workspaceRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains(".git"))
                    .limit(200)
                    .forEach(path -> scanFile(workspaceRoot, path, findings));
        } catch (IOException exception) {
            findings.add("WORKSPACE_SENSITIVE_SCAN_FAILED");
        }
        return unique(findings);
    }

    private void scanFile(
            Path workspaceRoot,
            Path path,
            List<String> findings
    ) {
        try {
            if (Files.size(path) > 512_000) {
                return;
            }
            String content = Files.readString(path);
            for (int i = 0; i < FORBIDDEN_PATTERNS.size(); i++) {
                if (FORBIDDEN_PATTERNS.get(i).matcher(content).find()) {
                    findings.add("FORBIDDEN_MARKER:"
                            + FORBIDDEN_MARKERS.get(i)
                            + ":"
                            + workspaceRoot.relativize(path));
                }
            }
        } catch (Exception exception) {
            findings.add("WORKSPACE_FILE_SCAN_FAILED:"
                    + workspaceRoot.relativize(path));
        }
    }

    private BitbucketWorkspacePushResponse response(
            ReplayCaseEntity replayCase,
            NormalizedPush request,
            boolean previewOnly,
            boolean executed,
            String commitSha,
            boolean bugfixBranchPushed,
            boolean integrationBranchPrepared,
            boolean mergeAttempted,
            boolean mergeSucceeded,
            boolean mergeConflict,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new BitbucketWorkspacePushResponse(
                replayCase.getId(),
                request.jiraKey(),
                previewOnly,
                executed,
                request.projectKey(),
                request.repositorySlug(),
                request.bugfixBranch(),
                request.integrationBranch(),
                nullToBlank(commitSha),
                bugfixBranchPushed,
                integrationBranchPrepared,
                mergeAttempted,
                mergeSucceeded,
                mergeConflict,
                unique(blockers),
                unique(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedPush normalize(
            ReplayCaseEntity replayCase,
            BitbucketWorkspacePushRequest request
    ) {
        BitbucketWorkspacePushRequest safe = safeRequest(request);
        String jiraKey = firstNonBlank(safe.jiraKey(), replayCase.getJiraKey());
        return new NormalizedPush(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                jiraKey,
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
                        safe.commitMessage(),
                        "ReplayLab: " + jiraKey + " guarded draft fix"
                )
        );
    }

    private BitbucketWorkspacePushRequest safeRequest(
            BitbucketWorkspacePushRequest request
    ) {
        return request == null
                ? new BitbucketWorkspacePushRequest(
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
                        "",
                        false,
                        false
                )
                : request;
    }

    private void saveEvidence(UUID caseId, BitbucketWorkspacePushResponse response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.BITBUCKET_WORKSPACE_PUSH);
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

    private boolean protectedBranch(String branch) {
        return properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(value -> value.equalsIgnoreCase(branch));
    }

    private List<String> nextActions(List<String> blockers) {
        if (blockers != null && !blockers.isEmpty()) {
            return List.of("Resolve workspace push blockers before creating a draft PR.");
        }
        return List.of("Create draft PR from integration branch to test2.");
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

    private record NormalizedPush(
            String projectKey,
            String repositorySlug,
            String jiraKey,
            String developmentBaseBranch,
            String environmentTargetBranch,
            String bugfixBranch,
            String integrationBranch,
            String commitMessage
    ) {
    }

    private record PlanValidation(
            List<String> blockers,
            List<String> warnings,
            Path workspaceRoot
    ) {
    }
}

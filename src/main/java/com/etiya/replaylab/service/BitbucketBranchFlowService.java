package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replaylab.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketMergeResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class BitbucketBranchFlowService {

    private static final String SOURCE = "replaylab-real-action-bitbucket-branch-flow";
    private static final Set<String> PROTECTED_BRANCHES = Set.of(
            "master",
            "test1",
            "test2",
            "preprod",
            "prod"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final ReplayLabProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketBranchFlowService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            ReplayLabProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.bitbucketClient = bitbucketClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BitbucketBranchFlowResponse preview(
            UUID caseId,
            BitbucketBranchFlowRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedBranchFlow normalized = normalize(replayCase, request);
        ValidationResult validation = validateNaming(normalized);
        List<String> warnings = new ArrayList<>(validation.warnings());
        warnings.add("PREVIEW_ONLY_NO_BRANCH_CREATED");
        return response(
                replayCase,
                normalized,
                true,
                false,
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
    public BitbucketBranchFlowResponse execute(
            UUID caseId,
            BitbucketBranchFlowRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketBranchFlowRequest safeRequest = safeRequest(request);
        validateExecute(safeRequest);
        NormalizedBranchFlow normalized = normalize(replayCase, safeRequest);
        ValidationResult validation = validateNaming(normalized);
        if (!validation.blockers().isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
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

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(validation.warnings());
        boolean bugfixCreated = false;
        boolean integrationCreated = false;
        boolean mergeAttempted = false;
        boolean mergeSucceeded = false;
        boolean mergeConflict = false;

        BitbucketBranchCheckResult base =
                bitbucketClient.branchExists(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.developmentBaseBranch()
                );
        if (!base.exists()) {
            blockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult target =
                bitbucketClient.branchExists(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.environmentTargetBranch()
                );
        if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }

        BitbucketBranchCheckResult bugfix =
                bitbucketClient.branchExists(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.bugfixBranch()
                );
        if (bugfix.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
        } else if (bugfix.exists()) {
            warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
        }

        BitbucketBranchCheckResult integration =
                bitbucketClient.branchExists(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.integrationBranch()
                );
        if (integration.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        } else if (integration.exists()) {
            warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
        }

        if (blockers.isEmpty()
                && !bugfix.exists()
                && safeRequest.createBugfixBranchFromMaster()) {
            BitbucketBranchCreateResult created =
                    bitbucketClient.createBranch(
                            normalized.projectKey(),
                            normalized.repositorySlug(),
                            normalized.bugfixBranch(),
                            normalized.developmentBaseBranch()
                    );
            bugfixCreated = created.created();
            warnings.addAll(created.warnings());
        }
        if (blockers.isEmpty()
                && !integration.exists()
                && safeRequest.createIntegrationBranchFromTarget()) {
            BitbucketBranchCreateResult created =
                    bitbucketClient.createBranch(
                            normalized.projectKey(),
                            normalized.repositorySlug(),
                            normalized.integrationBranch(),
                            normalized.environmentTargetBranch()
                    );
            integrationCreated = created.created();
            warnings.addAll(created.warnings());
        }

        if (blockers.isEmpty() && safeRequest.mergeBugfixIntoIntegration()) {
            if (!properties.getRealActions().isBitbucketMergeEnabled()) {
                blockers.add("BITBUCKET_MERGE_DISABLED");
            } else {
                BitbucketMergeResult merge = bitbucketClient.mergeBranches(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.bugfixBranch(),
                        normalized.integrationBranch()
                );
                mergeAttempted = merge.attempted();
                mergeSucceeded = merge.succeeded();
                mergeConflict = merge.conflict();
                warnings.addAll(merge.warnings());
                if (merge.conflict()) {
                    blockers.add("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
                } else if (!merge.succeeded()) {
                    blockers.add("BITBUCKET_BRANCH_MERGE_NOT_IMPLEMENTED");
                }
            }
        }

        boolean executed = blockers.isEmpty()
                && (bugfixCreated || integrationCreated || mergeSucceeded);
        BitbucketBranchFlowResponse response = response(
                replayCase,
                normalized,
                false,
                executed,
                bugfixCreated,
                integrationCreated,
                mergeAttempted,
                mergeSucceeded,
                mergeConflict,
                unique(blockers),
                unique(warnings),
                nextActions(blockers)
        );
        if (executed) {
            replayCase.setGeneratedBranch(normalized.integrationBranch());
            caseRepository.save(replayCase);
            saveEvidence(replayCase.getId(), response);
        }
        return response;
    }

    private void validateExecute(BitbucketBranchFlowRequest request) {
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
                || !properties.getRealActions().isBitbucketBranchCreateEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REAL_ACTIONS_DISABLED"
            );
        }
    }

    private ValidationResult validateNaming(NormalizedBranchFlow request) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (isBlank(request.projectKey())) {
            blockers.add("BITBUCKET_PROJECT_KEY_REQUIRED");
        }
        if (isBlank(request.repositorySlug())) {
            blockers.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
        }
        if (!request.bugfixBranch().startsWith(
                properties.getRealActions().getBugfixBranchPrefix())) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID");
        }
        if (!request.integrationBranch().startsWith(
                properties.getRealActions().getIntegrationBranchPrefix())) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        if (PROTECTED_BRANCHES.contains(request.bugfixBranch())
                || PROTECTED_BRANCHES.contains(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_WRITE_BLOCKED");
        }
        if (!request.integrationBranch().toLowerCase().contains(
                request.jiraKey().toLowerCase())) {
            warnings.add("INTEGRATION_BRANCH_DOES_NOT_INCLUDE_JIRA_KEY");
        }
        return new ValidationResult(unique(blockers), unique(warnings));
    }

    private BitbucketBranchFlowResponse response(
            ReplayCaseEntity replayCase,
            NormalizedBranchFlow request,
            boolean previewOnly,
            boolean executed,
            boolean bugfixCreated,
            boolean integrationCreated,
            boolean mergeAttempted,
            boolean mergeSucceeded,
            boolean mergeConflict,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new BitbucketBranchFlowResponse(
                replayCase.getId(),
                request.jiraKey(),
                previewOnly,
                executed,
                request.projectKey(),
                request.repositorySlug(),
                request.developmentBaseBranch(),
                request.environmentTargetBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                bugfixCreated,
                integrationCreated,
                mergeAttempted,
                mergeSucceeded,
                mergeConflict,
                unique(blockers),
                unique(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedBranchFlow normalize(
            ReplayCaseEntity replayCase,
            BitbucketBranchFlowRequest request
    ) {
        BitbucketBranchFlowRequest safe = safeRequest(request);
        String jiraKey = firstNonBlank(safe.jiraKey(), replayCase.getJiraKey());
        String targetBranch = firstNonBlank(
                safe.environmentTargetBranch(),
                properties.getRealActions().getDefaultEnvironmentTargetBranch()
        );
        return new NormalizedBranchFlow(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                jiraKey,
                firstNonBlank(
                        safe.developmentBaseBranch(),
                        properties.getRealActions().getDefaultDevelopmentBaseBranch()
                ),
                targetBranch,
                firstNonBlank(
                        safe.bugfixBranch(),
                        properties.getRealActions().getBugfixBranchPrefix() + jiraKey
                ),
                firstNonBlank(
                        safe.integrationBranch(),
                        properties.getRealActions().getIntegrationBranchPrefix() + jiraKey
                )
        );
    }

    private void saveEvidence(UUID caseId, BitbucketBranchFlowResponse response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.BITBUCKET_BRANCH_FLOW);
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

    private List<String> nextActions(List<String> blockers) {
        if (blockers != null && !blockers.isEmpty()) {
            return List.of("Resolve branch flow blockers before PR creation.");
        }
        return List.of("Use integration branch as PR source to test2.");
    }

    private BitbucketBranchFlowRequest safeRequest(
            BitbucketBranchFlowRequest request
    ) {
        return request == null
                ? new BitbucketBranchFlowRequest(
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
                        false,
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

    private record NormalizedBranchFlow(
            String projectKey,
            String repositorySlug,
            String jiraKey,
            String developmentBaseBranch,
            String environmentTargetBranch,
            String bugfixBranch,
            String integrationBranch
    ) {
    }

    private record ValidationResult(
            List<String> blockers,
            List<String> warnings
    ) {
    }
}

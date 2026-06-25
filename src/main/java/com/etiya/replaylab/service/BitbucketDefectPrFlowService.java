package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketDefectPrFlowRequest;
import com.etiya.replaylab.api.dto.BitbucketDefectPrFlowResponse;
import com.etiya.replaylab.api.dto.BitbucketBackendDemoPrRequest;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BitbucketDefectPrFlowService {

    private static final String SOURCE =
            "replaylab-real-action-bitbucket-defect-pr-flow";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final BackendDemoPrGitOperations gitOperations;
    private final ReplayLabProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketDefectPrFlowService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            BackendDemoPrGitOperations gitOperations,
            ReplayLabProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.bitbucketClient = bitbucketClient;
        this.gitOperations = gitOperations;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BitbucketDefectPrFlowResponse preview(
            UUID caseId,
            BitbucketDefectPrFlowRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedDefectPrFlow normalized = normalize(replayCase, request);
        List<String> blockers = new ArrayList<>(validate(normalized));
        List<String> warnings = new ArrayList<>();
        if (blockers.isEmpty()) {
            blockers.addAll(checkBranches(normalized, safeRequest(request), warnings).blockers());
        }
        warnings.add("PREVIEW_ONLY_NO_BRANCH_CREATED");
        warnings.add("PREVIEW_ONLY_NO_FILE_WRITTEN");
        warnings.add("PREVIEW_ONLY_NO_COMMIT");
        warnings.add("PREVIEW_ONLY_NO_PUSH");
        warnings.add("PREVIEW_ONLY_NO_PR_CREATED");
        return response(
                replayCase,
                normalized,
                false,
                true,
                "",
                "",
                "",
                "",
                List.of(),
                unique(blockers),
                unique(warnings),
                nextActions(blockers)
        );
    }

    @Transactional
    public BitbucketDefectPrFlowResponse create(
            UUID caseId,
            BitbucketDefectPrFlowRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketDefectPrFlowRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        NormalizedDefectPrFlow normalized = normalize(replayCase, safeRequest);
        List<String> blockers = new ArrayList<>(validate(normalized));
        List<String> warnings = new ArrayList<>();
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", List.of(),
                    unique(blockers), warnings, nextActions(blockers));
        }

        BranchChecks checks = checkBranches(normalized, safeRequest, warnings);
        blockers.addAll(checks.blockers());
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", List.of(),
                    unique(blockers), unique(warnings), nextActions(blockers));
        }

        boolean bugfixExists = checks.bugfixExists();
        boolean integrationExists = checks.integrationExists();
        if (!bugfixExists) {
            BitbucketBranchCreateResult created = bitbucketClient.createBranch(
                    normalized.projectKey(),
                    normalized.repositorySlug(),
                    normalized.bugfixBranch(),
                    normalized.sourceBaseBranch()
            );
            warnings.addAll(created.warnings());
            if (created.alreadyExists()
                    && !safeRequest.allowReuseExistingBranches()) {
                blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
            } else if (created.alreadyExists()) {
                warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
                bugfixExists = true;
            } else if (!created.created()) {
                blockers.add("BITBUCKET_BUGFIX_BRANCH_CREATE_FAILED");
            }
        }
        if (!integrationExists) {
            BitbucketBranchCreateResult created = bitbucketClient.createBranch(
                    normalized.projectKey(),
                    normalized.repositorySlug(),
                    normalized.integrationBranch(),
                    normalized.targetBaseBranch()
            );
            warnings.addAll(created.warnings());
            if (created.alreadyExists()
                    && !safeRequest.allowReuseExistingBranches()) {
                blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
            } else if (created.alreadyExists()) {
                warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
                integrationExists = true;
            } else if (!created.created()) {
                blockers.add("BITBUCKET_INTEGRATION_BRANCH_CREATE_FAILED");
            }
        }
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", List.of(),
                    unique(blockers), unique(warnings), nextActions(blockers));
        }

        BackendDemoPrGitOperations.BackendDemoPrGitResult pushed =
                gitOperations.commitPushAndPrepareIntegrationBranch(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.sourceBaseBranch(),
                        normalized.targetBaseBranch(),
                        normalized.bugfixBranch(),
                        normalized.integrationBranch(),
                        bugfixExists,
                        integrationExists,
                        normalized.generatedFilePath(),
                        generatedContent(normalized),
                        normalized.commitMessage()
                );
        warnings.addAll(nonBlank(pushed.warning()));
        if (pushed.mergeConflict()) {
            blockers.add("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
        } else if (!isBlank(pushed.error())) {
            blockers.add(pushed.error().contains("BITBUCKET_GIT_OPERATION_TIMEOUT")
                    ? "BITBUCKET_GIT_OPERATION_TIMEOUT"
                    : "BITBUCKET_DEFECT_PR_FLOW_PUSH_FAILED");
            warnings.add(pushed.error());
        } else if (!pushed.pushed()) {
            blockers.add("BITBUCKET_DEFECT_PR_FLOW_PUSH_FAILED");
        }
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    pushed.bugfixCommitSha(), pushed.integrationCommitSha(),
                    "", "", pushed.conflictedFiles(),
                    unique(blockers), unique(warnings), nextActions(blockers));
        }

        PullRequestResult pr = bitbucketClient.createPullRequest(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.integrationBranch(),
                normalized.targetBaseBranch(),
                normalized.title(),
                prDescription(replayCase, normalized),
                List.of()
        );
        BitbucketDefectPrFlowResponse response = response(
                replayCase,
                normalized,
                true,
                false,
                pushed.bugfixCommitSha(),
                pushed.integrationCommitSha(),
                nullToBlank(pr.id()),
                nullToBlank(pr.url()),
                List.of(),
                List.of(),
                unique(warnings),
                List.of("Review draft PR. Do not merge until human validation completes.")
        );
        replayCase.setGeneratedBranch(normalized.integrationBranch());
        replayCase.setPullRequestUrl(response.pullRequestUrl());
        caseRepository.save(replayCase);
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private BranchChecks checkBranches(
            NormalizedDefectPrFlow request,
            BitbucketDefectPrFlowRequest originalRequest,
            List<String> warnings
    ) {
        List<String> blockers = new ArrayList<>();
        BitbucketDefectPrFlowRequest safeRequest = safeRequest(originalRequest);
        BitbucketBranchCheckResult source = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.sourceBaseBranch());
        warnings.addAll(source.warnings());
        if (lookupFailed(source.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!source.exists()) {
            blockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult target = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.targetBaseBranch());
        warnings.addAll(target.warnings());
        if (lookupFailed(target.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult bugfix = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.bugfixBranch());
        warnings.addAll(bugfix.warnings());
        if (bugfix.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
        } else if (bugfix.exists()) {
            warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
        }
        BitbucketBranchCheckResult integration = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.integrationBranch());
        warnings.addAll(integration.warnings());
        if (integration.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        } else if (integration.exists()) {
            warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
        }
        return new BranchChecks(unique(blockers), bugfix.exists(), integration.exists());
    }

    private void validateCreate(BitbucketDefectPrFlowRequest request) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(",", errors));
        }
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isBitbucketBranchCreateEnabled()
                || !properties.getRealActions().isBitbucketPushEnabled()
                || !properties.getRealActions().isBitbucketPrCreateEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REAL_ACTIONS_DISABLED");
        }
    }

    private List<String> validate(NormalizedDefectPrFlow request) {
        List<String> blockers = new ArrayList<>();
        if (isBlank(request.projectKey())) blockers.add("BITBUCKET_PROJECT_KEY_REQUIRED");
        if (isBlank(request.repositorySlug())) blockers.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
        if (isBlank(request.defectKey())) blockers.add("DEFECT_KEY_REQUIRED");
        if (isBlank(request.defectSummary())) blockers.add("DEFECT_SUMMARY_REQUIRED");
        if (isBlank(request.targetBaseBranch())) blockers.add("TARGET_BASE_BRANCH_REQUIRED");
        if (!request.appliedSourceFix()
                && !request.appliedRegressionTest()
                && !request.appliedConfigChange()) {
            blockers.add("APPROVED_CHANGE_REQUIRED");
        }
        if (!request.bugfixBranch().startsWith("bugfix/")) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID");
        }
        if (!request.integrationBranch().toLowerCase(Locale.ROOT)
                .startsWith("integration/" + request.environment().toLowerCase(Locale.ROOT) + "/")) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        if (isProtectedBranch(request.bugfixBranch())
                || isProtectedBranch(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_WRITE_BLOCKED");
        }
        return unique(blockers);
    }

    private BitbucketDefectPrFlowResponse response(
            ReplayCaseEntity replayCase,
            NormalizedDefectPrFlow request,
            boolean created,
            boolean previewOnly,
            String bugfixCommitSha,
            String integrationCommitSha,
            String pullRequestId,
            String pullRequestUrl,
            List<String> conflictedFiles,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new BitbucketDefectPrFlowResponse(
                replayCase.getId(),
                request.defectKey(),
                request.defectSummary(),
                created,
                previewOnly,
                request.sourceBaseBranch(),
                request.targetBaseBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                request.commitMessage(),
                nullToBlank(bugfixCommitSha),
                nullToBlank(integrationCommitSha),
                nullToBlank(pullRequestId),
                nullToBlank(pullRequestUrl),
                request.title(),
                request.appliedSourceFix(),
                request.appliedRegressionTest(),
                request.appliedConfigChange(),
                unique(conflictedFiles),
                unique(blockers),
                unique(warnings).stream().map(this::sanitize).toList(),
                branchLookupDiagnostics(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedDefectPrFlow normalize(
            ReplayCaseEntity replayCase,
            BitbucketDefectPrFlowRequest request
    ) {
        BitbucketDefectPrFlowRequest safe = safeRequest(request);
        String defectKey = firstNonBlank(safe.defectKey(), replayCase.getJiraKey());
        String environment = firstNonBlank(safe.environment(), "test2");
        String sourceBase = firstNonBlank(
                safe.sourceBaseBranch(),
                properties.getIntegrations().getBitbucket().getBranchStrategy().getSourceBaseBranch()
        );
        String targetBase = firstNonBlank(
                safe.targetBaseBranch(),
                properties.getIntegrations().getBitbucket().getBranchStrategy()
                        .getEnvironmentTargets().get(environment)
        );
        String summary = normalizeSummary(defectKey, safe.defectSummary());
        String titlePrefix = firstNonBlank(
                safe.titlePrefix(),
                properties.getRealActions().getDraftPrTitlePrefix()
        );
        String title = titlePrefix + " " + defectKey + " fix proposal";
        if (!title.startsWith("[DRAFT]")) title = "[DRAFT] " + title;
        if (!title.contains("ReplayLab")) title = title.replace("[DRAFT]", "[DRAFT] ReplayLab");
        boolean sourceFix = safe.applyApprovedFix();
        boolean regressionTest = safe.applyApprovedRegressionTest();
        boolean configChange = safe.applyApprovedConfigChange();
        return new NormalizedDefectPrFlow(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                defectKey,
                summary,
                environment,
                sourceBase,
                targetBase,
                firstNonBlank(safe.bugfixBranch(), pattern(
                        properties.getIntegrations().getBitbucket().getBranchStrategy().getBugfixBranchPattern(),
                        environment,
                        defectKey
                )),
                firstNonBlank(safe.integrationBranch(), pattern(
                        properties.getIntegrations().getBitbucket().getBranchStrategy().getIntegrationBranchPattern(),
                        environment,
                        defectKey
                )),
                generatedPath(defectKey, sourceFix, regressionTest, configChange),
                defectKey + ": " + summary,
                title,
                sourceFix,
                regressionTest,
                configChange
        );
    }

    private String generatedPath(
            String defectKey,
            boolean sourceFix,
            boolean regressionTest,
            boolean configChange
    ) {
        if (regressionTest) {
            return "ControllerBackend/src/test/java/com/etiya/replaylab/generated/"
                    + compact(defectKey)
                    + "ReplayLabRegressionTest.java";
        }
        String suffix = configChange ? "CONFIG_CHANGE_PLAN.md" : "SOURCE_FIX_PLAN.md";
        return ".replaylab/approved-changes/" + defectKey + "/" + suffix;
    }

    private String generatedContent(NormalizedDefectPrFlow request) {
        if (request.appliedRegressionTest()) {
            return """
                    package com.etiya.replaylab.generated;

                    import org.junit.jupiter.api.Disabled;
                    import org.junit.jupiter.api.Test;

                    import static org.junit.jupiter.api.Assertions.assertTrue;

                    class %sReplayLabRegressionTest {

                        @Test
                        @Disabled("ReplayLab approved regression draft; requires review before enabling.")
                        void replaylab_regression_scenario_for_%s() {
                            assertTrue(true, "ReplayLab placeholder for %s");
                        }
                    }
                    """.formatted(compact(request.defectKey()), compact(request.defectKey()), request.defectKey());
        }
        return String.join("\n",
                "# ReplayLab Approved Change",
                "",
                "Defect: " + request.defectKey() + ": " + request.defectSummary(),
                "Approved source fix: " + request.appliedSourceFix(),
                "Approved regression test: " + request.appliedRegressionTest(),
                "Approved config change: " + request.appliedConfigChange(),
                "",
                "Human review required before merging.");
    }

    private String prDescription(ReplayCaseEntity replayCase, NormalizedDefectPrFlow request) {
        return sanitize(String.join("\n",
                "ReplayLab Draft PR",
                "",
                "Defect:",
                "- " + request.defectKey() + ": " + request.defectSummary(),
                "",
                "ReplayLab Case:",
                "- " + replayCase.getId(),
                "",
                "Branch Flow:",
                "- source base: " + request.sourceBaseBranch(),
                "- bugfix branch: " + request.bugfixBranch(),
                "- target base: " + request.targetBaseBranch(),
                "- integration branch: " + request.integrationBranch(),
                "- PR: " + request.integrationBranch() + " -> " + request.targetBaseBranch(),
                "",
                "Applied Changes:",
                "- approved source fix: " + request.appliedSourceFix(),
                "- approved regression test: " + request.appliedRegressionTest(),
                "- approved config change: " + request.appliedConfigChange(),
                "",
                "Guardrails:",
                "- No direct target branch push",
                "- No PR merge",
                "- No Jenkins trigger",
                "- No deployment trigger",
                "- Human review required"));
    }

    private String normalizeSummary(String defectKey, String summary) {
        String value = nullToBlank(summary).trim();
        if (isBlank(defectKey) || isBlank(value)) {
            return value;
        }
        return value.replaceFirst("(?i)^\\Q" + defectKey + "\\E\\s*:\\s*", "").trim();
    }

    private String pattern(String pattern, String environment, String defectKey) {
        return firstNonBlank(pattern, "")
                .replace("{environment}", environment)
                .replace("{defectKey}", defectKey);
    }

    private BitbucketDefectPrFlowRequest safeRequest(BitbucketDefectPrFlowRequest request) {
        return request == null
                ? new BitbucketDefectPrFlowRequest("", "", "", "", "", "", "",
                "", "", "", "", true, false, false, false, false, false)
                : request;
    }

    public BitbucketDefectPrFlowRequest fromBackendDemoRequest(
            BitbucketBackendDemoPrRequest request
    ) {
        BitbucketBackendDemoPrRequest safe = request == null
                ? new BitbucketBackendDemoPrRequest("", "", "", "", "", "", "", "", "", "",
                true, true, false, false)
                : request;
        return new BitbucketDefectPrFlowRequest(
                safe.requestedBy(),
                safe.projectKey(),
                safe.repositorySlug(),
                safe.defectNo(),
                safe.defectSummary(),
                "test2",
                safe.sourceBaseBranch(),
                safe.targetBaseBranch(),
                safe.bugfixBranch(),
                safe.integrationBranch(),
                safe.titlePrefix(),
                safe.allowReuseExistingBranches(),
                false,
                safe.testOnly(),
                false,
                safe.confirmCreate(),
                safe.guardrailsAccepted()
        );
    }

    private void saveEvidence(UUID caseId, BitbucketDefectPrFlowResponse response) {
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
                        HttpStatus.NOT_FOUND, "Replay case not found: " + caseId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private boolean lookupFailed(List<String> warnings) {
        return warnings != null && warnings.stream()
                .anyMatch("BITBUCKET_BRANCH_LOOKUP_FAILED"::equals);
    }

    private Map<String, Object> branchLookupDiagnostics(List<String> warnings) {
        if (warnings == null) return Map.of();
        List<Map<String, Object>> diagnostics = warnings.stream()
                .filter(value -> value != null && value.startsWith("BRANCH_LOOKUP_DIAGNOSTICS"))
                .map(this::parseDiagnostic)
                .filter(value -> !value.isEmpty())
                .toList();
        if (diagnostics.isEmpty()) return Map.of();
        Map<String, Object> value = new LinkedHashMap<>(diagnostics.get(0));
        value.put("allDiagnostics", diagnostics);
        return Map.copyOf(value);
    }

    private Map<String, Object> parseDiagnostic(String diagnostic) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (String part : diagnostic.split("\\|")) {
            if (!part.contains("=")) continue;
            int index = part.indexOf('=');
            String key = part.substring(0, index);
            String raw = part.substring(index + 1);
            switch (key) {
                case "requested" -> value.put("branchRequested", raw);
                case "normalized" -> value.put("branchNormalized", raw);
                case "strategies" -> value.put("lookupStrategiesTried",
                        raw.isBlank() ? List.of() : List.of(raw.split(",")));
                case "httpStatuses" -> value.put("httpStatuses", raw);
                case "matchedBranchId" -> value.put("matchedBranchId", raw);
                case "matchedDisplayId" -> value.put("matchedDisplayId", raw);
                default -> {
                    // Ignore unknown diagnostic keys.
                }
            }
        }
        return Map.copyOf(value);
    }

    private List<String> nextActions(List<String> blockers) {
        if (blockers != null && !blockers.isEmpty()) {
            return List.of("Resolve defect PR flow blockers before creating a draft PR.");
        }
        return List.of("Review draft PR. Do not merge until human validation completes.");
    }

    private boolean isProtectedBranch(String value) {
        String normalized = nullToBlank(value);
        return properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(branch -> branch.equalsIgnoreCase(normalized));
    }

    private String sanitize(String value) {
        if (value == null) return "";
        if (value.startsWith("BITBUCKET_GIT_CREDENTIAL_DIAGNOSTICS")
                || value.startsWith("BRANCH_LOOKUP_DIAGNOSTICS")) {
            return value;
        }
        return value
                .replaceAll("https://[^\\s']+@", "https://[redacted]@")
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)apikey", "[redacted]")
                .replaceAll("(?i)privatekey", "[redacted]");
    }

    private String compact(String value) {
        return firstNonBlank(value, "case").replaceAll("[^A-Za-z0-9]", "");
    }

    private List<String> nonBlank(String value) {
        return isBlank(value) ? List.of() : List.of(value);
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null ? List.of() : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()));
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record NormalizedDefectPrFlow(
            String projectKey,
            String repositorySlug,
            String defectKey,
            String defectSummary,
            String environment,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            String generatedFilePath,
            String commitMessage,
            String title,
            boolean appliedSourceFix,
            boolean appliedRegressionTest,
            boolean appliedConfigChange
    ) {
    }

    private record BranchChecks(
            List<String> blockers,
            boolean bugfixExists,
            boolean integrationExists
    ) {
    }
}

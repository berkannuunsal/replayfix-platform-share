package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BitbucketBackendDemoPrService {

    private static final String SOURCE =
            "replayfix-real-action-bitbucket-backend-demo-pr";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final BackendDemoPrGitOperations gitOperations;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketBackendDemoPrService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            BackendDemoPrGitOperations gitOperations,
            ReplayFixProperties properties,
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
    public BitbucketBackendDemoPrResponse preview(
            UUID caseId,
            BitbucketBackendDemoPrRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedBackendDemoPr normalized = normalize(replayCase, request);
        List<String> blockers = new ArrayList<>(validateNaming(normalized));
        List<String> warnings = new ArrayList<>();
        if (blockers.isEmpty()) {
            BranchChecks checks = checkBranches(normalized, safeRequest(request), warnings);
            blockers.addAll(checks.blockers());
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
                unique(blockers),
                unique(warnings),
                nextActions(blockers)
        );
    }

    @Transactional
    public BitbucketBackendDemoPrResponse create(
            UUID caseId,
            BitbucketBackendDemoPrRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketBackendDemoPrRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        NormalizedBackendDemoPr normalized = normalize(replayCase, safeRequest);
        List<String> blockers = new ArrayList<>(validateNaming(normalized));
        List<String> warnings = new ArrayList<>();
        if (!blockers.isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    "",
                    "",
                    "",
                    "",
                    blockers,
                    warnings,
                    nextActions(blockers)
            );
        }

        BranchChecks checks = checkBranches(normalized, safeRequest, warnings);
        blockers.addAll(checks.blockers());
        if (!blockers.isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    "",
                    "",
                    "",
                    "",
                    unique(blockers),
                    unique(warnings),
                    nextActions(blockers)
            );
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
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    "",
                    "",
                    "",
                    "",
                    unique(blockers),
                    unique(warnings),
                    nextActions(blockers)
            );
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
                        generatedTestContent(normalized.defectNo()),
                        normalized.commitMessage()
                );
        warnings.addAll(nonBlank(pushed.warning()));
        if (pushed.mergeConflict()) {
            blockers.add("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
        } else if (!isBlank(pushed.error())) {
            blockers.add("BITBUCKET_BACKEND_DEMO_PR_PUSH_FAILED");
            warnings.add(pushed.error());
        } else if (!pushed.pushed()) {
            blockers.add("BITBUCKET_BACKEND_DEMO_PR_PUSH_FAILED");
        }
        if (!blockers.isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    pushed.bugfixCommitSha(),
                    pushed.integrationCommitSha(),
                    "",
                    "",
                    unique(blockers),
                    unique(warnings),
                    nextActions(blockers)
            );
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
        BitbucketBackendDemoPrResponse response = response(
                replayCase,
                normalized,
                true,
                false,
                pushed.bugfixCommitSha(),
                pushed.integrationCommitSha(),
                nullToBlank(pr.id()),
                nullToBlank(pr.url()),
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
            NormalizedBackendDemoPr request,
            BitbucketBackendDemoPrRequest originalRequest,
            List<String> warnings
    ) {
        List<String> blockers = new ArrayList<>();
        BitbucketBackendDemoPrRequest safeRequest = safeRequest(originalRequest);

        BitbucketBranchCheckResult source = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                request.sourceBaseBranch()
        );
        warnings.addAll(source.warnings());
        if (lookupFailed(source.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!source.exists()) {
            blockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }

        BitbucketBranchCheckResult target = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                request.targetBaseBranch()
        );
        warnings.addAll(target.warnings());
        if (lookupFailed(target.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }

        BitbucketBranchCheckResult bugfix = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                request.bugfixBranch()
        );
        warnings.addAll(bugfix.warnings());
        if (bugfix.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
        } else if (bugfix.exists()) {
            warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
        }

        BitbucketBranchCheckResult integration = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                request.integrationBranch()
        );
        warnings.addAll(integration.warnings());
        if (integration.exists() && !safeRequest.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        } else if (integration.exists()) {
            warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
        }
        return new BranchChecks(
                unique(blockers),
                bugfix.exists(),
                integration.exists()
        );
    }

    private void validateCreate(BitbucketBackendDemoPrRequest request) {
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

    private List<String> validateNaming(NormalizedBackendDemoPr request) {
        List<String> blockers = new ArrayList<>();
        if (isBlank(request.projectKey())) {
            blockers.add("BITBUCKET_PROJECT_KEY_REQUIRED");
        }
        if (isBlank(request.repositorySlug())) {
            blockers.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
        }
        if (isBlank(request.defectSummary())) {
            blockers.add("DEFECT_SUMMARY_REQUIRED");
        }
        if (!request.bugfixBranch().startsWith(
                properties.getRealActions().getBugfixBranchPrefix())) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID");
        }
        if (!isIntegrationBranch(request.integrationBranch())) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        if (isProtectedBranch(request.bugfixBranch())
                || isProtectedBranch(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_WRITE_BLOCKED");
        }
        if (isProtectedBranch(request.sourceBaseBranch())
                && request.sourceBaseBranch().equals(request.bugfixBranch())) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (isProtectedBranch(request.targetBaseBranch())
                && request.targetBaseBranch().equals(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (!request.generatedFilePath().contains("/src/test/java/")
                && !request.generatedFilePath().startsWith("src/test/java/")) {
            blockers.add("TEST_SOURCE_PATH_NOT_FOUND");
        }
        return unique(blockers);
    }

    private BitbucketBackendDemoPrResponse response(
            ReplayCaseEntity replayCase,
            NormalizedBackendDemoPr request,
            boolean created,
            boolean previewOnly,
            String bugfixCommitSha,
            String integrationCommitSha,
            String pullRequestId,
            String pullRequestUrl,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new BitbucketBackendDemoPrResponse(
                replayCase.getId(),
                request.defectNo(),
                request.defectSummary(),
                created,
                previewOnly,
                request.sourceBaseBranch(),
                request.targetBaseBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                request.generatedFilePath(),
                request.commitMessage(),
                nullToBlank(bugfixCommitSha),
                nullToBlank(integrationCommitSha),
                nullToBlank(pullRequestId),
                nullToBlank(pullRequestUrl),
                request.title(),
                unique(blockers),
                unique(warnings).stream()
                        .map(this::sanitize)
                        .toList(),
                branchLookupDiagnostics(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedBackendDemoPr normalize(
            ReplayCaseEntity replayCase,
            BitbucketBackendDemoPrRequest request
    ) {
        BitbucketBackendDemoPrRequest safe = safeRequest(request);
        String defectNo = firstNonBlank(safe.defectNo(), replayCase.getJiraKey());
        String sourceBase = firstNonBlank(
                safe.sourceBaseBranch(),
                properties.getRealActions().getDefaultDevelopmentBaseBranch()
        );
        String targetBase = firstNonBlank(
                safe.targetBaseBranch(),
                properties.getRealActions().getDefaultEnvironmentTargetBranch()
        );
        String titlePrefix = firstNonBlank(
                safe.titlePrefix(),
                properties.getRealActions().getDraftPrTitlePrefix()
        );
        String title = titlePrefix + " " + defectNo + " demo regression test";
        if (!title.startsWith("[DRAFT]")) {
            title = "[DRAFT] " + title;
        }
        if (!title.contains("ReplayFix")) {
            title = title.replace("[DRAFT]", "[DRAFT] ReplayFix");
        }
        String defectSummary = nullToBlank(safe.defectSummary());
        return new NormalizedBackendDemoPr(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                defectNo,
                defectSummary,
                sourceBase,
                targetBase,
                firstNonBlank(
                        safe.bugfixBranch(),
                        properties.getRealActions().getBugfixBranchPrefix() + defectNo
                ),
                firstNonBlank(
                        safe.integrationBranch(),
                        "Integration/test2/" + defectNo
                ),
                generatedTestRelativePath(defectNo),
                defectNo + ": " + defectSummary,
                title
        );
    }

    private BitbucketBackendDemoPrRequest safeRequest(
            BitbucketBackendDemoPrRequest request
    ) {
        return request == null
                ? new BitbucketBackendDemoPrRequest(
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
                        true,
                        false,
                        false
                )
                : request;
    }

    private String generatedTestRelativePath(String defectNo) {
        return "ControllerBackend/src/test/java/com/company/replayfix/generated/"
                + compact(defectNo)
                + "ReplayFixDemoRegressionTest.java";
    }

    private String generatedTestContent(String defectNo) {
        String safeDefect = firstNonBlank(defectNo, "UNKNOWN");
        return """
                package com.company.replayfix.generated;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class %sReplayFixDemoRegressionTest {

                    @Test
                    @Disabled("ReplayFix generated demo regression placeholder; requires review before enabling.")
                    void replayfix_generated_regression_scenario_for_%s() {
                        assertTrue(true, "ReplayFix demo placeholder for %s");
                    }
                }
                """.formatted(
                compact(safeDefect),
                compact(safeDefect),
                safeDefect
        );
    }

    private String prDescription(
            ReplayCaseEntity replayCase,
            NormalizedBackendDemoPr request
    ) {
        return sanitize(String.join(
                "\n",
                "ReplayFix Draft Demo PR",
                "",
                "Defect:",
                "- " + request.defectNo() + ": " + request.defectSummary(),
                "",
                "ReplayFix Case:",
                "- " + replayCase.getId(),
                "",
                "Branch Flow:",
                "- source base: " + request.sourceBaseBranch(),
                "- bugfix branch: " + request.bugfixBranch(),
                "- target base: " + request.targetBaseBranch(),
                "- integration branch: " + request.integrationBranch(),
                "- PR: " + request.integrationBranch()
                        + " -> " + request.targetBaseBranch(),
                "",
                "Generated file:",
                "- " + request.generatedFilePath(),
                "",
                "Guardrails:",
                "- Test-only change",
                "- No production source modification",
                "- No direct protected branch push",
                "- Bugfix-to-integration merge/cherry-pick only",
                "- No PR merge",
                "- No Jenkins trigger",
                "- Human review required"
        ));
    }

    private List<String> nextActions(List<String> blockers) {
        if (blockers != null && !blockers.isEmpty()) {
            return List.of("Resolve backend demo PR blockers before creating a draft PR.");
        }
        return List.of("Review draft PR. Do not merge until human validation completes.");
    }

    private void saveEvidence(UUID caseId, BitbucketBackendDemoPrResponse response) {
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private boolean lookupFailed(List<String> warnings) {
        return warnings != null
                && warnings.stream()
                .anyMatch("BITBUCKET_BRANCH_LOOKUP_FAILED"::equals);
    }

    private Map<String, Object> branchLookupDiagnostics(List<String> warnings) {
        if (warnings == null) {
            return Map.of();
        }
        List<Map<String, Object>> diagnostics = warnings.stream()
                .filter(value -> value != null
                        && value.startsWith("BRANCH_LOOKUP_DIAGNOSTICS"))
                .map(this::parseDiagnostic)
                .filter(value -> !value.isEmpty())
                .toList();
        if (diagnostics.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>(diagnostics.get(0));
        value.put("allDiagnostics", diagnostics);
        return Map.copyOf(value);
    }

    private Map<String, Object> parseDiagnostic(String diagnostic) {
        Map<String, Object> value = new LinkedHashMap<>();
        String[] parts = diagnostic.split("\\|");
        for (String part : parts) {
            if (!part.contains("=")) {
                continue;
            }
            int index = part.indexOf('=');
            String key = part.substring(0, index);
            String raw = part.substring(index + 1);
            switch (key) {
                case "requested" -> value.put("branchRequested", raw);
                case "normalized" -> value.put("branchNormalized", raw);
                case "strategies" -> value.put(
                        "lookupStrategiesTried",
                        raw.isBlank() ? List.of() : List.of(raw.split(","))
                );
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

    private boolean isIntegrationBranch(String value) {
        String normalized = nullToBlank(value).toLowerCase(Locale.ROOT);
        String configured = nullToBlank(properties.getRealActions()
                .getIntegrationBranchPrefix()).toLowerCase(Locale.ROOT);
        return normalized.startsWith(configured)
                || normalized.startsWith("integration/test2/");
    }

    private boolean isProtectedBranch(String value) {
        String normalized = nullToBlank(value);
        return properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(branch -> branch.equalsIgnoreCase(normalized));
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
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)apikey", "[redacted]")
                .replaceAll("(?i)privatekey", "[redacted]");
    }

    private String compact(String value) {
        return firstNonBlank(value, "case")
                .replaceAll("[^A-Za-z0-9]", "");
    }

    private List<String> nonBlank(String value) {
        return isBlank(value) ? List.of() : List.of(value);
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

    private record NormalizedBackendDemoPr(
            String projectKey,
            String repositorySlug,
            String defectNo,
            String defectSummary,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            String generatedFilePath,
            String commitMessage,
            String title
    ) {
    }

    private record BranchChecks(
            List<String> blockers,
            boolean bugfixExists,
            boolean integrationExists
    ) {
    }
}

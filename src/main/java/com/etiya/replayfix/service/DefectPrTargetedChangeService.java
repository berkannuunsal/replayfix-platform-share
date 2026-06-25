package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.DefectPrTargetedChangeRequest;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketFileUpdateResult;
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
public class DefectPrTargetedChangeService {

    private static final String SOURCE =
            "replayfix-real-action-bitbucket-defect-targeted-change";
    private static final String APPROVED_REGRESSION_TEST = "APPROVED_REGRESSION_TEST";
    private static final String TARGETED_TEST_CHANGE = "TARGETED_TEST_CHANGE";
    private static final String APPROVED_SOURCE_FIX = "APPROVED_SOURCE_FIX";
    private static final String APPROVED_CONFIG_CHANGE = "APPROVED_CONFIG_CHANGE";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public DefectPrTargetedChangeService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.bitbucketClient = bitbucketClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DefectPrTargetedChangeResponse preview(
            UUID caseId,
            DefectPrTargetedChangeRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedTargetedChange normalized = normalize(replayCase, request);
        List<String> blockers = new ArrayList<>(validate(normalized));
        List<String> warnings = new ArrayList<>();
        if (blockers.isEmpty()) {
            blockers.addAll(checkBranches(normalized, warnings).blockers());
        }
        warnings.add("PREVIEW_ONLY_NO_BRANCH_CREATED");
        warnings.add("PREVIEW_ONLY_NO_FILE_UPDATED");
        warnings.add("PREVIEW_ONLY_NO_PR_CREATED");
        warnings.add("NO_REPOSITORY_CLONE");
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
    public DefectPrTargetedChangeResponse create(
            UUID caseId,
            DefectPrTargetedChangeRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        DefectPrTargetedChangeRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        NormalizedTargetedChange normalized = normalize(replayCase, safeRequest);
        List<String> blockers = new ArrayList<>(validate(normalized));
        List<String> warnings = new ArrayList<>();
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", unique(blockers), warnings, nextActions(blockers));
        }

        BranchChecks checks = checkBranches(normalized, warnings);
        blockers.addAll(checks.blockers());
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", unique(blockers), unique(warnings), nextActions(blockers));
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
            warnings.addAll(sanitize(created.warnings()));
            if (created.alreadyExists() && !normalized.allowReuseExistingBranches()) {
                blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
            } else if (created.alreadyExists()) {
                warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
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
            warnings.addAll(sanitize(created.warnings()));
            if (created.alreadyExists() && !normalized.allowReuseExistingBranches()) {
                blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
            } else if (created.alreadyExists()) {
                warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
            } else if (!created.created()) {
                blockers.add("BITBUCKET_INTEGRATION_BRANCH_CREATE_FAILED");
            }
        }
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    "", "", "", "", unique(blockers), unique(warnings), nextActions(blockers));
        }

        String content = generatedContent(normalized);
        BitbucketFileUpdateResult bugfixUpdate = bitbucketClient.updateFile(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.bugfixBranch(),
                normalized.filePath(),
                content,
                normalized.commitMessage()
        );
        warnings.addAll(sanitize(bugfixUpdate.warnings()));
        if (!bugfixUpdate.updated()) {
            blockers.add("BITBUCKET_BUGFIX_FILE_UPDATE_FAILED");
        }

        BitbucketFileUpdateResult integrationUpdate = bitbucketClient.updateFile(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.integrationBranch(),
                normalized.filePath(),
                content,
                normalized.commitMessage()
        );
        warnings.addAll(sanitize(integrationUpdate.warnings()));
        if (!integrationUpdate.updated()) {
            blockers.add("BITBUCKET_INTEGRATION_FILE_UPDATE_FAILED");
        }
        if (!blockers.isEmpty()) {
            return response(replayCase, normalized, false, false,
                    bugfixUpdate.commitSha(), integrationUpdate.commitSha(),
                    "", "", unique(blockers), unique(warnings), nextActions(blockers));
        }

        PullRequestResult existing = bitbucketClient.findOpenPullRequest(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.integrationBranch(),
                normalized.targetBaseBranch()
        );
        String prId;
        String prUrl;
        if (existing != null && !isBlank(existing.url())) {
            prId = nullToBlank(existing.id());
            prUrl = nullToBlank(existing.url());
            warnings.add("BITBUCKET_PULL_REQUEST_ALREADY_EXISTS_REUSED");
        } else {
            PullRequestResult created = bitbucketClient.createPullRequest(
                    normalized.projectKey(),
                    normalized.repositorySlug(),
                    normalized.integrationBranch(),
                    normalized.targetBaseBranch(),
                    normalized.title(),
                    prDescription(replayCase, normalized),
                    List.of()
            );
            prId = created == null ? "" : nullToBlank(created.id());
            prUrl = created == null ? "" : nullToBlank(created.url());
        }

        DefectPrTargetedChangeResponse response = response(
                replayCase,
                normalized,
                true,
                false,
                bugfixUpdate.commitSha(),
                integrationUpdate.commitSha(),
                prId,
                prUrl,
                List.of(),
                unique(warnings),
                List.of("Review draft PR. Do not merge or trigger Jenkins automatically.")
        );
        replayCase.setGeneratedBranch(normalized.integrationBranch());
        replayCase.setPullRequestUrl(response.pullRequestUrl());
        caseRepository.save(replayCase);
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private BranchChecks checkBranches(
            NormalizedTargetedChange request,
            List<String> warnings
    ) {
        List<String> blockers = new ArrayList<>();
        BitbucketBranchCheckResult source = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.sourceBaseBranch());
        warnings.addAll(sanitize(source.warnings()));
        if (lookupFailed(source.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!source.exists()) {
            blockers.add("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult target = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.targetBaseBranch());
        warnings.addAll(sanitize(target.warnings()));
        if (lookupFailed(target.warnings())) {
            blockers.add("BITBUCKET_BRANCH_LOOKUP_FAILED");
        } else if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }
        BitbucketBranchCheckResult bugfix = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.bugfixBranch());
        warnings.addAll(sanitize(bugfix.warnings()));
        if (bugfix.exists() && !request.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS");
        } else if (bugfix.exists()) {
            warnings.add("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS_REUSED");
        }
        BitbucketBranchCheckResult integration = bitbucketClient.branchExists(
                request.projectKey(), request.repositorySlug(), request.integrationBranch());
        warnings.addAll(sanitize(integration.warnings()));
        if (integration.exists() && !request.allowReuseExistingBranches()) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        } else if (integration.exists()) {
            warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
        }
        return new BranchChecks(unique(blockers), bugfix.exists(), integration.exists());
    }

    private List<String> validate(NormalizedTargetedChange request) {
        List<String> blockers = new ArrayList<>();
        if (isBlank(request.projectKey())) blockers.add("BITBUCKET_PROJECT_KEY_REQUIRED");
        if (isBlank(request.repositorySlug())) blockers.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
        if (isBlank(request.defectKey())) blockers.add("DEFECT_KEY_REQUIRED");
        if (isBlank(request.defectSummary())) blockers.add("DEFECT_SUMMARY_REQUIRED");
        if (isBlank(request.targetBaseBranch())) blockers.add("TARGET_BASE_BRANCH_REQUIRED");
        if (isBlank(request.sourceBaseBranch())) blockers.add("SOURCE_BASE_BRANCH_REQUIRED");
        if (isBlank(request.bugfixBranch()) || !request.bugfixBranch().startsWith("bugfix/")) {
            blockers.add("BUGFIX_BRANCH_PREFIX_REQUIRED");
        }
        String integrationPrefix = "integration/" + request.environment() + "/";
        if (isBlank(request.integrationBranch())
                || !request.integrationBranch().toLowerCase(Locale.ROOT)
                .startsWith(integrationPrefix.toLowerCase(Locale.ROOT))) {
            blockers.add("INTEGRATION_BRANCH_PREFIX_REQUIRED");
        }
        if (isProtectedBranch(request.bugfixBranch()) || isProtectedBranch(request.integrationBranch())) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (isUnsafePath(request.filePath())) {
            blockers.add("FILE_PATH_GUARD_FAILED");
        }
        if (APPROVED_REGRESSION_TEST.equals(request.changeMode())
                || TARGETED_TEST_CHANGE.equals(request.changeMode())) {
            if (!request.filePath().startsWith("ControllerBackend/src/test/java/")) {
                blockers.add("TEST_FILE_PATH_REQUIRED");
            }
        } else if (APPROVED_SOURCE_FIX.equals(request.changeMode())) {
            blockers.add("SOURCE_FIX_NOT_APPROVED_FOR_FILE");
        } else if (APPROVED_CONFIG_CHANGE.equals(request.changeMode())) {
            blockers.add("APPROVED_CHANGE_REQUIRED");
        } else {
            blockers.add("CHANGE_MODE_NOT_SUPPORTED");
        }
        return unique(blockers);
    }

    private void validateCreate(DefectPrTargetedChangeRequest request) {
        List<String> blockers = new ArrayList<>();
        if (isBlank(request.requestedBy())) blockers.add("REQUESTED_BY_REQUIRED");
        if (!request.confirmCreate()) blockers.add("CONFIRM_CREATE_REQUIRED");
        if (!request.guardrailsAccepted()) blockers.add("GUARDRAILS_ACCEPTED_REQUIRED");
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isBitbucketBranchCreateEnabled()
                || !properties.getRealActions().isBitbucketPushEnabled()
                || !properties.getRealActions().isBitbucketPrCreateEnabled()) {
            blockers.add("REAL_ACTIONS_DISABLED");
        }
        if (!blockers.isEmpty()) {
            throw new ResponseStatusException(
                    blockers.contains("REAL_ACTIONS_DISABLED")
                            ? HttpStatus.CONFLICT
                            : HttpStatus.BAD_REQUEST,
                    String.join(",", blockers)
            );
        }
    }

    private DefectPrTargetedChangeResponse response(
            ReplayCaseEntity replayCase,
            NormalizedTargetedChange request,
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
        return new DefectPrTargetedChangeResponse(
                replayCase.getId(),
                request.defectKey(),
                request.defectSummary(),
                created,
                previewOnly,
                request.sourceBaseBranch(),
                request.targetBaseBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                request.filePath(),
                request.changeMode(),
                request.commitMessage(),
                nullToBlank(bugfixCommitSha),
                nullToBlank(integrationCommitSha),
                nullToBlank(pullRequestId),
                nullToBlank(pullRequestUrl),
                request.title(),
                APPROVED_SOURCE_FIX.equals(request.changeMode()),
                APPROVED_REGRESSION_TEST.equals(request.changeMode())
                        || TARGETED_TEST_CHANGE.equals(request.changeMode()),
                APPROVED_CONFIG_CHANGE.equals(request.changeMode()),
                unique(blockers),
                sanitize(unique(warnings)),
                branchLookupDiagnostics(warnings),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedTargetedChange normalize(
            ReplayCaseEntity replayCase,
            DefectPrTargetedChangeRequest request
    ) {
        DefectPrTargetedChangeRequest safe = safeRequest(request);
        String defectKey = firstNonBlank(safe.defectKey(), replayCase.getJiraKey());
        String environment = firstNonBlank(safe.environment(), "test2");
        ReplayFixProperties.BranchStrategy strategy =
                properties.getIntegrations().getBitbucket().getBranchStrategy();
        String sourceBase = firstNonBlank(safe.sourceBaseBranch(), strategy.getSourceBaseBranch());
        String targetBase = firstNonBlank(
                safe.targetBaseBranch(),
                strategy.getEnvironmentTargets().get(environment)
        );
        String summary = normalizeSummary(defectKey, safe.defectSummary());
        String changeMode = firstNonBlank(safe.changeMode(), APPROVED_REGRESSION_TEST).toUpperCase(Locale.ROOT);
        String titlePrefix = firstNonBlank(
                safe.titlePrefix(),
                properties.getRealActions().getDraftPrTitlePrefix()
        );
        String bugfixBranch = firstNonBlank(
                safe.bugfixBranch(),
                expand(strategy.getBugfixBranchPattern(), environment, defectKey)
        );
        String integrationBranch = firstNonBlank(
                safe.integrationBranch(),
                expand(strategy.getIntegrationBranchPattern(), environment, defectKey)
        );
        String filePath = firstNonBlank(safe.filePath(), generatedTestRelativePath(defectKey));
        return new NormalizedTargetedChange(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                defectKey,
                summary,
                environment,
                sourceBase,
                targetBase,
                bugfixBranch,
                integrationBranch,
                normalizePath(filePath),
                changeMode,
                defectKey + ": " + summary,
                titlePrefix + " " + defectKey + " fix proposal",
                safe.allowReuseExistingBranches()
        );
    }

    private DefectPrTargetedChangeRequest safeRequest(
            DefectPrTargetedChangeRequest request
    ) {
        return request == null
                ? new DefectPrTargetedChangeRequest(
                "", "", "", "", "", "", "", "", "", "", "", "",
                "", true, false, false)
                : request;
    }

    private String generatedTestRelativePath(String defectKey) {
        return "ControllerBackend/src/test/java/com/etiya/replayfix/generated/"
                + compact(defectKey)
                + "RegressionTest.java";
    }

    private String generatedContent(NormalizedTargetedChange request) {
        if (!APPROVED_REGRESSION_TEST.equals(request.changeMode())
                && !TARGETED_TEST_CHANGE.equals(request.changeMode())) {
            return "";
        }
        return """
                package com.etiya.replayfix.generated;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class %sRegressionTest {

                    @Test
                    @Disabled("ReplayFix generated regression test; requires business-specific assertions before enabling.")
                    void should_cover_%s_regression_scenario() {
                        assertTrue(true, "ReplayFix generated regression placeholder for %s");
                    }
                }
                """.formatted(compact(request.defectKey()), methodSuffix(request.defectKey()), request.defectKey());
    }

    private String prDescription(ReplayCaseEntity replayCase, NormalizedTargetedChange request) {
        return sanitize(String.join("\n",
                "ReplayFix Draft PR",
                "",
                "Defect:",
                "- " + request.defectKey() + ": " + request.defectSummary(),
                "",
                "ReplayFix Case:",
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
                "- change mode: " + request.changeMode(),
                "- file path: " + request.filePath(),
                "- source fix applied: " + APPROVED_SOURCE_FIX.equals(request.changeMode()),
                "- regression test applied: " + (APPROVED_REGRESSION_TEST.equals(request.changeMode())
                        || TARGETED_TEST_CHANGE.equals(request.changeMode())),
                "- config change applied: " + APPROVED_CONFIG_CHANGE.equals(request.changeMode()),
                "",
                "Guardrails:",
                "- No direct target branch push",
                "- No PR merge",
                "- No Jenkins trigger",
                "- No deployment trigger",
                "- Human review required"
        ));
    }

    private void saveEvidence(
            UUID caseId,
            DefectPrTargetedChangeResponse response
    ) {
        try {
            EvidenceEntity evidence = new EvidenceEntity();
            evidence.setCaseId(caseId);
            evidence.setEvidenceType(EvidenceType.PULL_REQUEST);
            evidence.setSource(SOURCE);
            evidence.setSanitized(true);
            evidence.setContentText(toJson(response));
            evidenceRepository.save(evidence);
        } catch (Exception ignored) {
            // Do not fail the guarded action after Bitbucket already accepted it.
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Case not found: " + caseId));
    }

    private String normalizeSummary(String defectKey, String summary) {
        String value = nullToBlank(summary).trim();
        if (isBlank(defectKey) || isBlank(value)) {
            return value;
        }
        String prefixPattern = "(?i)^" + java.util.regex.Pattern.quote(defectKey)
                + "\\s*:?\\s*";
        return value.replaceFirst(prefixPattern, "").trim();
    }

    private String expand(String pattern, String environment, String defectKey) {
        return firstNonBlank(pattern, "")
                .replace("{environment}", firstNonBlank(environment, "test2"))
                .replace("{defectKey}", firstNonBlank(defectKey, "UNKNOWN"));
    }

    private String normalizePath(String value) {
        return nullToBlank(value).replace('\\', '/').trim();
    }

    private boolean isUnsafePath(String value) {
        String path = normalizePath(value);
        String lower = path.toLowerCase(Locale.ROOT);
        return path.isBlank()
                || path.contains("..")
                || path.startsWith("/")
                || path.matches("^[A-Za-z]:/.*")
                || lower.startsWith("http://")
                || lower.startsWith("https://");
    }

    private boolean isProtectedBranch(String value) {
        String normalized = nullToBlank(value);
        return properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(branch -> branch.equalsIgnoreCase(normalized));
    }

    private List<String> nextActions(List<String> blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return List.of("Review draft PR. Do not merge or trigger Jenkins automatically.");
        }
        return blockers.stream()
                .map(blocker -> "Resolve blocker: " + blocker)
                .toList();
    }

    private boolean lookupFailed(List<String> warnings) {
        return warnings != null && warnings.stream()
                .anyMatch(warning -> warning != null
                        && warning.contains("BITBUCKET_BRANCH_LOOKUP_FAILED"));
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)authorization[^\\s,;]*", "Authorization[redacted]")
                .replaceAll("(?i)bearer[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)cookie[^\\s,;]*", "Cookie[redacted]")
                .replaceAll("(?i)token[^\\s,;]*", "token[redacted]")
                .replaceAll("(?i)password[^\\s,;]*", "password[redacted]")
                .replaceAll("(?i)secret[^\\s,;]*", "secret[redacted]")
                .replaceAll("(?i)apikey[^\\s,;]*", "apiKey[redacted]")
                .replaceAll("(?i)privatekey[^\\s,;]*", "privateKey[redacted]");
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::sanitize)
                .toList();
    }

    private Map<String, Object> branchLookupDiagnostics(List<String> warnings) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (warnings == null) {
            return diagnostics;
        }
        warnings.stream()
                .filter(warning -> warning != null
                        && warning.startsWith("BRANCH_LOOKUP_DIAGNOSTICS"))
                .forEach(warning -> {
                    String[] parts = warning.split("\\|");
                    Map<String, Object> value = new LinkedHashMap<>();
                    for (int i = 1; i < parts.length; i++) {
                        int separator = parts[i].indexOf('=');
                        if (separator < 0) {
                            continue;
                        }
                        String key = parts[i].substring(0, separator);
                        String raw = parts[i].substring(separator + 1);
                        switch (key) {
                            case "requested" -> value.put("branchRequested", raw);
                            case "normalized" -> value.put("branchNormalized", raw);
                            case "strategies" -> value.put("lookupStrategiesTried",
                                    raw.isBlank() ? List.of() : List.of(raw.split(",")));
                            case "httpStatuses" -> value.put("httpStatuses", raw);
                            case "matchedBranchId" -> value.put("matchedBranchId", raw);
                            case "matchedDisplayId" -> value.put("matchedDisplayId", raw);
                            default -> {
                            }
                        }
                    }
                    Object key = value.get("branchNormalized");
                    if (key instanceof String branch && !branch.isBlank()) {
                        diagnostics.put(branch, value);
                    }
                });
        return diagnostics;
    }

    private String methodSuffix(String value) {
        String suffix = firstNonBlank(value, "case")
                .replaceAll("[^A-Za-z0-9]", "_");
        return suffix.matches("^[A-Za-z_].*") ? suffix : "_" + suffix;
    }

    private String compact(String value) {
        return firstNonBlank(value, "case").replaceAll("[^A-Za-z0-9]", "");
    }

    private List<String> unique(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
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

    private record BranchChecks(
            List<String> blockers,
            boolean bugfixExists,
            boolean integrationExists
    ) {
    }

    private record NormalizedTargetedChange(
            String projectKey,
            String repositorySlug,
            String defectKey,
            String defectSummary,
            String environment,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            String filePath,
            String changeMode,
            String commitMessage,
            String title,
            boolean allowReuseExistingBranches
    ) {
    }
}

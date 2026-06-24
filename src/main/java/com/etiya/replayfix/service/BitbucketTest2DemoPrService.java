package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrResponse;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class BitbucketTest2DemoPrService {

    private static final String SOURCE =
            "replayfix-real-action-bitbucket-test2-demo-pr";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final Test2DemoPrGitOperations gitOperations;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketTest2DemoPrService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            Test2DemoPrGitOperations gitOperations,
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
    public BitbucketTest2DemoPrResponse preview(
            UUID caseId,
            BitbucketTest2DemoPrRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        NormalizedDemoPr normalized = normalize(replayCase, request);
        List<String> blockers = validateNaming(normalized);
        List<String> warnings = new ArrayList<>();
        warnings.add("PREVIEW_ONLY_NO_BRANCH_CREATED");
        warnings.add("PREVIEW_ONLY_NO_FILE_WRITTEN");
        warnings.add("PREVIEW_ONLY_NO_PR_CREATED");
        return response(
                replayCase,
                normalized,
                true,
                false,
                "",
                "",
                "",
                blockers,
                warnings,
                nextActions(blockers)
        );
    }

    @Transactional
    public BitbucketTest2DemoPrResponse create(
            UUID caseId,
            BitbucketTest2DemoPrRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        BitbucketTest2DemoPrRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        NormalizedDemoPr normalized = normalize(replayCase, safeRequest);
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
                    blockers,
                    warnings,
                    nextActions(blockers)
            );
        }

        BitbucketBranchCheckResult target = targetBranchExists(normalized);
        if (!target.exists()) {
            blockers.add("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        }
        warnings.addAll(target.warnings());

        BitbucketBranchCheckResult integration =
                bitbucketClient.branchExists(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.integrationBranch()
                );
        if (integration.exists()
                && !safeRequest.allowReuseExistingIntegrationBranch()) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        } else if (integration.exists()) {
            warnings.add("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS_REUSED");
        }
        warnings.addAll(integration.warnings());
        if (!blockers.isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    "",
                    "",
                    "",
                    blockers,
                    warnings,
                    nextActions(blockers)
            );
        }

        if (!integration.exists()) {
            BitbucketBranchCreateResult created =
                    bitbucketClient.createBranch(
                            normalized.projectKey(),
                            normalized.repositorySlug(),
                            normalized.integrationBranch(),
                            normalized.targetBranch()
                    );
            warnings.addAll(created.warnings());
            if (!created.created() && !created.alreadyExists()) {
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
                    blockers,
                    warnings,
                    nextActions(blockers)
            );
        }

        Test2DemoPrGitOperations.Test2DemoPrGitResult pushed =
                gitOperations.commitAndPushIntegrationBranch(
                        normalized.projectKey(),
                        normalized.repositorySlug(),
                        normalized.targetBranch(),
                        normalized.integrationBranch(),
                        normalized.generatedFilePath(),
                        generatedTestContent(normalized.jiraKey()),
                        "ReplayFix: "
                                + normalized.jiraKey()
                                + " demo regression test"
                );
        warnings.addAll(nonBlank(pushed.warning()));
        if (!isBlank(pushed.error())) {
            blockers.add("BITBUCKET_TEST2_DEMO_PR_PUSH_FAILED");
            warnings.add(pushed.error());
        }
        if (!blockers.isEmpty()) {
            return response(
                    replayCase,
                    normalized,
                    false,
                    false,
                    pushed.commitSha(),
                    "",
                    "",
                    blockers,
                    warnings,
                    nextActions(blockers)
            );
        }

        PullRequestResult pr = bitbucketClient.createPullRequest(
                normalized.projectKey(),
                normalized.repositorySlug(),
                normalized.integrationBranch(),
                normalized.targetBranch(),
                normalized.title(),
                prDescription(replayCase, normalized),
                List.of()
        );
        BitbucketTest2DemoPrResponse response = response(
                replayCase,
                normalized,
                false,
                true,
                pushed.commitSha(),
                nullToBlank(pr.id()),
                nullToBlank(pr.url()),
                List.of(),
                warnings,
                List.of("Review draft PR. Do not merge until human validation completes.")
        );
        replayCase.setGeneratedBranch(normalized.integrationBranch());
        replayCase.setPullRequestUrl(response.pullRequestUrl());
        caseRepository.save(replayCase);
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private void validateCreate(BitbucketTest2DemoPrRequest request) {
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

    private List<String> validateNaming(NormalizedDemoPr request) {
        List<String> blockers = new ArrayList<>();
        if (!"test2".equals(request.targetBranch())) {
            blockers.add("BITBUCKET_TEST2_TARGET_REQUIRED");
        }
        if (!request.integrationBranch().startsWith(
                properties.getRealActions().getIntegrationBranchPrefix())) {
            blockers.add("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
        }
        if (properties.getRealActions().getProtectedBranches().stream()
                .anyMatch(branch -> branch.equalsIgnoreCase(
                        request.integrationBranch()
                ))) {
            blockers.add("PROTECTED_BRANCH_PUSH_BLOCKED");
        }
        if (!request.generatedFilePath().contains("/src/test/java/")
                && !request.generatedFilePath().startsWith("src/test/java/")) {
            blockers.add("TEST_SOURCE_PATH_NOT_FOUND");
        }
        return unique(blockers);
    }

    private BitbucketBranchCheckResult targetBranchExists(
            NormalizedDemoPr request
    ) {
        BitbucketBranchCheckResult direct = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                request.targetBranch()
        );
        if (direct.exists()) {
            return direct;
        }
        BitbucketBranchCheckResult ref = bitbucketClient.branchExists(
                request.projectKey(),
                request.repositorySlug(),
                "refs/heads/" + request.targetBranch()
        );
        if (ref.exists()) {
            List<String> warnings = new ArrayList<>(direct.warnings());
            warnings.addAll(ref.warnings());
            return new BitbucketBranchCheckResult(
                    true,
                    request.targetBranch(),
                    unique(warnings)
            );
        }
        List<String> warnings = new ArrayList<>(direct.warnings());
        warnings.addAll(ref.warnings());
        return new BitbucketBranchCheckResult(
                false,
                request.targetBranch(),
                unique(warnings)
        );
    }

    private BitbucketTest2DemoPrResponse response(
            ReplayCaseEntity replayCase,
            NormalizedDemoPr request,
            boolean previewOnly,
            boolean created,
            String commitSha,
            String pullRequestId,
            String pullRequestUrl,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new BitbucketTest2DemoPrResponse(
                replayCase.getId(),
                request.jiraKey(),
                previewOnly,
                created,
                request.projectKey(),
                request.repositorySlug(),
                request.targetBranch(),
                request.integrationBranch(),
                request.generatedFilePath(),
                commitSha,
                pullRequestId,
                pullRequestUrl,
                request.title(),
                unique(blockers),
                unique(warnings).stream()
                        .map(this::sanitize)
                        .toList(),
                unique(nextActions),
                Instant.now()
        );
    }

    private NormalizedDemoPr normalize(
            ReplayCaseEntity replayCase,
            BitbucketTest2DemoPrRequest request
    ) {
        BitbucketTest2DemoPrRequest safe = safeRequest(request);
        String jiraKey = firstNonBlank(safe.jiraKey(), replayCase.getJiraKey());
        String targetBranch = normalizeBranch(firstNonBlank(
                safe.targetBranch(),
                properties.getRealActions().getDefaultEnvironmentTargetBranch()
        ));
        String integrationBranch = firstNonBlank(
                safe.integrationBranch(),
                properties.getRealActions().getIntegrationBranchPrefix()
                        + jiraKey
        );
        String titlePrefix = firstNonBlank(
                safe.titlePrefix(),
                properties.getRealActions().getDraftPrTitlePrefix()
        );
        String title = titlePrefix + " " + jiraKey + " demo regression test";
        if (!title.startsWith("[DRAFT]")) {
            title = "[DRAFT] " + title;
        }
        if (!title.contains("ReplayFix")) {
            title = title.replace("[DRAFT]", "[DRAFT] ReplayFix");
        }
        return new NormalizedDemoPr(
                firstNonBlank(safe.projectKey(), "DCE"),
                firstNonBlank(safe.repositorySlug(), "backend"),
                jiraKey,
                targetBranch,
                integrationBranch,
                generatedTestRelativePath(jiraKey),
                title
        );
    }

    private String normalizeBranch(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("refs/heads/")
                ? value.substring("refs/heads/".length())
                : value;
    }

    private BitbucketTest2DemoPrRequest safeRequest(
            BitbucketTest2DemoPrRequest request
    ) {
        return request == null
                ? new BitbucketTest2DemoPrRequest(
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

    private String generatedTestRelativePath(String jiraKey) {
        return "ControllerBackend/src/test/java/com/company/replayfix/generated/"
                + jiraCompact(jiraKey)
                + "ReplayFixDemoRegressionTest.java";
    }

    private String generatedTestContent(String jiraKey) {
        String safeJira = firstNonBlank(jiraKey, "UNKNOWN");
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
                jiraCompact(safeJira),
                jiraCompact(safeJira),
                safeJira
        );
    }

    private String prDescription(
            ReplayCaseEntity replayCase,
            NormalizedDemoPr request
    ) {
        return sanitize(String.join(
                "\n",
                "ReplayFix Draft Demo PR",
                "",
                "Source Jira:",
                "- " + request.jiraKey(),
                "",
                "ReplayFix Case:",
                "- " + replayCase.getId(),
                "",
                "Branch Flow:",
                "- source branch: " + request.integrationBranch(),
                "- target branch: " + request.targetBranch(),
                "- created from: " + request.targetBranch(),
                "",
                "Generated file:",
                "- " + request.generatedFilePath(),
                "",
                "Guardrails:",
                "- Test-only change",
                "- No production source modification",
                "- No auto-merge",
                "- No Jenkins trigger",
                "- Human review required"
        ));
    }

    private List<String> nextActions(List<String> blockers) {
        if (blockers != null && !blockers.isEmpty()) {
            return List.of("Resolve test2 demo PR blockers before creating a draft PR.");
        }
        return List.of("Review draft PR. Do not merge until validation completes.");
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private void saveEvidence(UUID caseId, BitbucketTest2DemoPrResponse response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.PULL_REQUEST);
        entity.setSource(SOURCE);
        entity.setSanitized(true);
        entity.setContentText(toJson(response));
        evidenceRepository.save(entity);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
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

    private String jiraCompact(String jiraKey) {
        return firstNonBlank(jiraKey, "case")
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

    private record NormalizedDemoPr(
            String projectKey,
            String repositorySlug,
            String jiraKey,
            String targetBranch,
            String integrationBranch,
            String generatedFilePath,
            String title
    ) {
    }
}

package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.StepResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.integration.*;
import com.etiya.replayfix.model.IntegrationModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ReplayOrchestrator {
    private final ReplayFixProperties properties;
    private final ReplayCaseService caseService;
    private final EvidenceService evidenceService;
    private final JiraClient jiraClient;
    private final LokiClient lokiClient;
    private final TempoClient tempoClient;
    private final KnowledgeClient knowledgeClient;
    private final AiClient aiClient;
    private final KubernetesClient kubernetesClient;
    private final GitWorkspaceService gitWorkspaceService;
    private final JenkinsClient jenkinsClient;
    private final BitbucketClient bitbucketClient;
    private final ObjectMapper objectMapper;
    private final JiraAdfTextExtractor jiraAdfTextExtractor;
    private final IncidentSignalExtractor incidentSignalExtractor;
    private final LokiQueryPlanner lokiQueryPlanner;
    private final AdaptiveLokiSearchService adaptiveLokiSearchService;

    public ReplayOrchestrator(
            ReplayFixProperties properties,
            ReplayCaseService caseService,
            EvidenceService evidenceService,
            JiraClient jiraClient,
            LokiClient lokiClient,
            TempoClient tempoClient,
            KnowledgeClient knowledgeClient,
            AiClient aiClient,
            KubernetesClient kubernetesClient,
            GitWorkspaceService gitWorkspaceService,
            JenkinsClient jenkinsClient,
            BitbucketClient bitbucketClient,
            ObjectMapper objectMapper,
            JiraAdfTextExtractor jiraAdfTextExtractor,
            IncidentSignalExtractor incidentSignalExtractor,
            LokiQueryPlanner lokiQueryPlanner,
            AdaptiveLokiSearchService adaptiveLokiSearchService
    ) {
        this.properties = properties;
        this.caseService = caseService;
        this.evidenceService = evidenceService;
        this.jiraClient = jiraClient;
        this.lokiClient = lokiClient;
        this.tempoClient = tempoClient;
        this.knowledgeClient = knowledgeClient;
        this.aiClient = aiClient;
        this.kubernetesClient = kubernetesClient;
        this.gitWorkspaceService = gitWorkspaceService;
        this.jenkinsClient = jenkinsClient;
        this.bitbucketClient = bitbucketClient;
        this.objectMapper = objectMapper;
        this.jiraAdfTextExtractor = jiraAdfTextExtractor;
        this.incidentSignalExtractor = incidentSignalExtractor;
        this.lokiQueryPlanner = lokiQueryPlanner;
        this.adaptiveLokiSearchService = adaptiveLokiSearchService;
    }

    public StepResponse collectContext(UUID id) {
        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.CONTEXT_COLLECTING,
                null
        );

        try {
            var issue = jiraClient.getIssue(replayCase.getJiraKey());
            evidenceService.save(
                    id,
                    EvidenceType.JIRA_ISSUE,
                    "jira",
                    objectMapper.writeValueAsString(issue),
                    true
            );

            Instant center = replayCase.getIncidentTime() == null
                    ? Instant.now()
                    : replayCase.getIncidentTime();

            String plainDescription =
                    jiraAdfTextExtractor.extract(
                            issue.description()
                    );

            var signals =
                    incidentSignalExtractor.extract(
                            issue.summary()
                                    + "\n"
                                    + plainDescription
                    );

            var queryPlan =
                    lokiQueryPlanner.plan(
                            issue.key(),
                            issue.summary(),
                            plainDescription,
                            signals
                    );

            evidenceService.save(
                    id,
                    EvidenceType.LOKI_QUERY_PLAN,
                    "replayfix-query-planner",
                    objectMapper.writeValueAsString(queryPlan),
                    true
            );

            var searchResult =
                    adaptiveLokiSearchService.search(
                            queryPlan,
                            center.minus(30, ChronoUnit.MINUTES),
                            center.plus(30, ChronoUnit.MINUTES),
                            50,
                            Math.min(
                                    300,
                                    properties.getPolicy().getMaxLogLines()
                            )
                    );

            evidenceService.save(
                    id,
                    EvidenceType.LOKI_LOG,
                    "loki-adaptive-search",
                    objectMapper.writeValueAsString(searchResult),
                    true
            );

            if (replayCase.getTraceId() != null
                    && !replayCase.getTraceId().isBlank()) {
                var trace = tempoClient.getTrace(replayCase.getTraceId());
                evidenceService.save(
                        id,
                        EvidenceType.TEMPO_TRACE,
                        "tempo",
                        trace.rawJson(),
                        true
                );
            }

            String knowledgeQuery =
                issue.summary()
                    + " "
                    + String.join(" ", signals.endpoints())
                    + " "
                    + String.join(" ", signals.businessTerms())
                    + " "
                    + String.join(" ", signals.errorCodes());

            for (var knowledge : knowledgeClient.search(
                knowledgeQuery
            )) {
                evidenceService.save(
                        id,
                        EvidenceType.CONFLUENCE_PAGE,
                        knowledge.source(),
                        objectMapper.writeValueAsString(knowledge),
                        true
                );
            }

            var rootCause = aiClient.analyzeRootCause(evidenceJson(id));
            evidenceService.save(
                    id,
                    EvidenceType.AI_ROOT_CAUSE,
                    "internal-ai",
                    objectMapper.writeValueAsString(rootCause),
                    true
            );

            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.CONTEXT_READY,
                    null
            );

            return StepResponse.success(
                    id,
                    "collect-context",
                    "Incident context and adaptive Loki search completed.",
                    Map.of(
                            "logCount",
                            searchResult.logs().size(),

                            "queryCount",
                            searchResult.attempts().size(),

                            "confidence",
                            rootCause.confidence(),

                            "rootCause",
                            rootCause.probableRootCause()
                    )
            );
        } catch (Exception e) {
            String errorMessage = rootCauseMessage(e);

            caseService.updateStatus(
                id,
                ReplayCaseStatus.INSUFFICIENT_EVIDENCE,
                errorMessage
            );

            throw new IllegalStateException(
                "Context collection failed: " + errorMessage,
                e
            );
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
            + ": "
            + root.getMessage();
    }

    public StepResponse provision(UUID id) {
        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.ENVIRONMENT_PROVISIONING,
                null
        );

        try {
            ReplayFixProperties.Target target = target(replayCase);
            String namespace = safeNamespace(
                    properties.getNamespacePrefix()
                            + "-"
                            + replayCase.getJiraKey().toLowerCase()
                            + "-"
                            + replayCase.getId().toString().substring(0, 6)
            );

            replayCase = caseService.updateNamespace(id, namespace);
            var result = kubernetesClient.provision(replayCase, target);

            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.ENVIRONMENT_READY,
                    null
            );

            return StepResponse.success(
                    id,
                    "provision",
                    "Isolated Kubernetes replay environment prepared.",
                    Map.of(
                            "namespace", result.namespace(),
                            "manifest", result.manifestPath()
                    )
            );
        } catch (Exception e) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.FAILED,
                    e.getMessage()
            );
            throw new IllegalStateException(
                    "Environment provisioning failed",
                    e
            );
        }
    }

    public StepResponse replay(UUID id) {
        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.REPLAYING,
                null
        );

        try {
            var result = kubernetesClient.replay(
                    replayCase,
                    target(replayCase)
            );

            evidenceService.save(
                    id,
                    EvidenceType.REPLAY_OUTPUT,
                    "kubernetes",
                    objectMapper.writeValueAsString(result),
                    true
            );

            caseService.updateStatus(
                    id,
                    result.reproduced()
                            ? ReplayCaseStatus.REPRODUCED
                            : ReplayCaseStatus.REPRODUCTION_FAILED,
                    null
            );

            jiraClient.addComment(
                    replayCase.getJiraKey(),
                    result.reproduced()
                            ? "ReplayFix reproduced the incident in namespace "
                                + replayCase.getNamespace()
                                + ". Failure signature: "
                                + result.failureSignature()
                            : "ReplayFix could not reproduce the incident. "
                                + "Review replay data and dependencies."
            );

            return StepResponse.success(
                    id,
                    "replay",
                    result.reproduced()
                            ? "Incident reproduced."
                            : "Incident was not reproduced.",
                    Map.of(
                            "reproduced", result.reproduced(),
                            "status", result.actualStatus(),
                            "failureSignature", result.failureSignature()
                    )
            );
        } catch (Exception e) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.REPRODUCTION_FAILED,
                    e.getMessage()
            );
            throw new IllegalStateException("Replay failed", e);
        }
    }

    public StepResponse generateTest(UUID id) {
        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.TEST_GENERATING,
                null
        );

        try {
            Path workspace = gitWorkspaceService.prepare(
                    replayCase,
                    target(replayCase)
            );

            var generated = aiClient.generateRegressionTest(
                    evidenceJson(id),
                    sourceContext(workspace)
            );

            gitWorkspaceService.writeFiles(
                    workspace,
                    generated.files()
            );

            evidenceService.save(
                    id,
                    EvidenceType.GENERATED_TEST,
                    "internal-ai",
                    objectMapper.writeValueAsString(generated),
                    true
            );

            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.TEST_GENERATED,
                    null
            );

            return StepResponse.success(
                    id,
                    "generate-test",
                    "Regression test generated.",
                    Map.of(
                            "fileCount", generated.files().size(),
                            "workspace", workspace.toString()
                    )
            );
        } catch (Exception e) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.FAILED,
                    e.getMessage()
            );
            throw new IllegalStateException(
                    "Test generation failed",
                    e
            );
        }
    }

    public StepResponse generatePatch(UUID id) {
        if (!properties.getPolicy().isAllowGeneratedCodeWrite()
                && properties.getMode() == ReplayFixProperties.Mode.LIVE) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.POLICY_BLOCKED,
                    "Generated code write is disabled"
            );
            throw new IllegalStateException(
                    "Policy blocks generated code modification"
            );
        }

        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.PATCH_GENERATING,
                null
        );

        try {
            Path workspace = gitWorkspaceService.prepare(
                    replayCase,
                    target(replayCase)
            );

            var generated = aiClient.generatePatch(
                    evidenceJson(id),
                    sourceContext(workspace),
                    "Use the latest replay output as failure context."
            );

            gitWorkspaceService.writeFiles(
                    workspace,
                    generated.files()
            );

            evidenceService.save(
                    id,
                    EvidenceType.GENERATED_PATCH,
                    "internal-ai",
                    objectMapper.writeValueAsString(generated),
                    true
            );

            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.PATCH_GENERATED,
                    null
            );

            return StepResponse.success(
                    id,
                    "generate-patch",
                    "Minimum patch proposal generated.",
                    Map.of(
                            "fileCount", generated.files().size(),
                            "workspace", workspace.toString()
                    )
            );
        } catch (Exception e) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.FAILED,
                    e.getMessage()
            );
            throw new IllegalStateException(
                    "Patch generation failed",
                    e
            );
        }
    }

    public StepResponse publishAndValidate(UUID id) {
        ReplayCaseEntity replayCase = caseService.updateStatus(
                id,
                ReplayCaseStatus.VALIDATING,
                null
        );

        try {
            ReplayFixProperties.Target target = target(replayCase);
            Path workspace = gitWorkspaceService.prepare(
                    replayCase,
                    target
            );

            var publish = gitWorkspaceService.commitAndPush(
                    replayCase,
                    target,
                    workspace
            );
            caseService.updateBranch(id, publish.branch());

            var build = jenkinsClient.runValidation(Map.of(
                    "REPOSITORY", target.getRepository(),
                    "BRANCH", publish.branch(),
                    "CASE_ID", id.toString(),
                    "RUN_INTEGRATION_TESTS", "true"
            ));

            evidenceService.save(
                    id,
                    EvidenceType.JENKINS_RESULT,
                    "jenkins",
                    objectMapper.writeValueAsString(build),
                    true
            );

            if (!"SUCCESS".equalsIgnoreCase(build.status())) {
                caseService.updateStatus(
                        id,
                        ReplayCaseStatus.VALIDATION_FAILED,
                        build.status()
                );

                return StepResponse.success(
                        id,
                        "publish-validate",
                        "Validation failed.",
                        Map.of(
                                "buildStatus", build.status(),
                                "buildUrl", build.buildUrl()
                        )
                );
            }

            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.VALIDATED,
                    null
            );

            if (properties.getPolicy().isAllowPullRequestCreation()
                    || properties.getMode() == ReplayFixProperties.Mode.DRY_RUN) {
                var pullRequest = bitbucketClient.createPullRequest(
                        target,
                        publish.branch(),
                        target.getGit().getSourceBranch(),
                        "[ReplayFix][REVIEW REQUIRED] "
                                + replayCase.getJiraKey(),
                        pullRequestDescription(replayCase, build),
                        target.getGit().getReviewerUsers()
                );

                caseService.updatePullRequest(
                        id,
                        pullRequest.url()
                );

                evidenceService.save(
                        id,
                        EvidenceType.PULL_REQUEST,
                        "bitbucket",
                        objectMapper.writeValueAsString(pullRequest),
                        true
                );

                caseService.updateStatus(
                        id,
                        properties.getPolicy().isRequireHumanApproval()
                                ? ReplayCaseStatus.AWAITING_HUMAN_REVIEW
                                : ReplayCaseStatus.PR_CREATED,
                        null
                );

                jiraClient.addComment(
                        replayCase.getJiraKey(),
                        "ReplayFix validation succeeded. Pull request: "
                                + pullRequest.url()
                );

                return StepResponse.success(
                        id,
                        "publish-validate",
                        "Branch validated and pull request created.",
                        Map.of(
                                "branch", publish.branch(),
                                "buildUrl", build.buildUrl(),
                                "pullRequest", pullRequest.url()
                        )
                );
            }

            return StepResponse.success(
                    id,
                    "publish-validate",
                    "Branch validated. Pull request creation is disabled.",
                    Map.of(
                            "branch", publish.branch(),
                            "buildUrl", build.buildUrl()
                    )
            );
        } catch (Exception e) {
            caseService.updateStatus(
                    id,
                    ReplayCaseStatus.VALIDATION_FAILED,
                    e.getMessage()
            );
            throw new IllegalStateException(
                    "Publish/validation failed",
                    e
            );
        }
    }

    public List<StepResponse> runAll(UUID id) {
        List<StepResponse> result = new ArrayList<>();
        result.add(collectContext(id));
        result.add(provision(id));

        StepResponse replay = replay(id);
        result.add(replay);

        if (!Boolean.TRUE.equals(
                replay.details().get("reproduced")
        )) {
            return result;
        }

        result.add(generateTest(id));
        result.add(generatePatch(id));
        result.add(publishAndValidate(id));
        return result;
    }

    public StepResponse cleanup(UUID id) {
        ReplayCaseEntity replayCase = caseService.get(id);
        kubernetesClient.cleanup(replayCase.getNamespace());

        caseService.updateStatus(
                id,
                ReplayCaseStatus.COMPLETED,
                null
        );

        return StepResponse.success(
                id,
                "cleanup",
                "Replay namespace removed.",
                Map.of(
                        "namespace",
                        Objects.toString(replayCase.getNamespace(), "")
                )
        );
    }

    private ReplayFixProperties.Target target(
            ReplayCaseEntity replayCase
    ) {
        ReplayFixProperties.Target target = properties.getTargets()
                .get(replayCase.getTargetKey());

        if (target == null) {
            throw new IllegalArgumentException(
                    "Unknown target: " + replayCase.getTargetKey()
            );
        }

        if (replayCase.getImageTag() != null
                && !replayCase.getImageTag().isBlank()) {
            target.setImage(replayCase.getImageTag());
        }
        return target;
    }

    private String evidenceJson(UUID id) {
        try {
            String json = objectMapper.writeValueAsString(
                    evidenceService.list(id)
            );
            int max = properties.getPolicy()
                    .getMaxAiInputCharacters();
            return json.length() > max
                    ? json.substring(0, max)
                    : json;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sourceContext(Path workspace) {
        try {
            if (!Files.exists(workspace)) return "";

            StringBuilder content = new StringBuilder();
            try (var paths = Files.walk(workspace)) {
                paths.filter(Files::isRegularFile)
                        .filter(path ->
                                path.toString().endsWith(".java")
                                        || path.toString().endsWith(".xml")
                                        || path.toString().endsWith(".yml")
                                        || path.toString().endsWith(".yaml")
                        )
                        .limit(30)
                        .forEach(path -> {
                            try {
                                content.append("\n--- FILE: ")
                                        .append(workspace.relativize(path))
                                        .append(" ---\n");
                                String fileContent = Files.readString(path);
                                content.append(
                                        fileContent,
                                        0,
                                        Math.min(
                                                fileContent.length(),
                                                12000
                                        )
                                );
                            } catch (Exception ignored) {
                            }
                        });
            }

            int max = properties.getPolicy()
                    .getMaxAiInputCharacters();
            return content.length() > max
                    ? content.substring(0, max)
                    : content.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String safeNamespace(String value) {
        String normalized = value.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-");
        return normalized.length() <= 63
                ? normalized
                : normalized.substring(0, 63);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private String pullRequestDescription(
            ReplayCaseEntity replayCase,
            IntegrationModels.BuildResult build
    ) {
        return "## ReplayFix Analysis\n\n"
                + "Jira: "
                + replayCase.getJiraKey()
                + "\n\n"
                + "- Incident reproduced in isolated namespace: `"
                + replayCase.getNamespace()
                + "`\n"
                + "- Validation status: **"
                + build.status()
                + "**\n"
                + "- Build: "
                + build.buildUrl()
                + "\n"
                + "- Human review is mandatory before merge.\n";
    }
}

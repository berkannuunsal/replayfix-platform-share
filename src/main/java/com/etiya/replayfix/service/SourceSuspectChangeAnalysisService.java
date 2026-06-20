package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceFlowAnchor;
import com.etiya.replayfix.model.SourceRecentCommit;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChange;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SourceSuspectChangeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(
            SourceSuspectChangeAnalysisService.class
    );

    public static final String SOURCE_CHANGE_ANALYSIS_FAILED =
            "SOURCE_CHANGE_ANALYSIS_FAILED";
    public static final String SOURCE_DISCOVERY_FAILED =
            "SOURCE_DISCOVERY_FAILED";
    public static final String SOURCE_GIT_HISTORY_FAILED =
            "SOURCE_GIT_HISTORY_FAILED";
    public static final String SOURCE_REASONING_CONTEXT_FAILED =
            "SOURCE_REASONING_CONTEXT_FAILED";
    public static final String SOURCE_WORKSPACE_NOT_FOUND =
            "SOURCE_WORKSPACE_NOT_FOUND";
    public static final String NO_FLOW_ANCHOR_FOUND =
            "NO_FLOW_ANCHOR_FOUND";
    public static final String NO_ENDPOINT_MATCH_FOUND =
            "NO_ENDPOINT_MATCH_FOUND";
    public static final String NO_RECENT_COMMITS_FOUND =
            "NO_RECENT_COMMITS_FOUND";
    public static final String ONLY_GENERIC_MATCHES_FOUND =
            "ONLY_GENERIC_MATCHES_FOUND";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final SuspectSignalExtractionService signalExtractionService;
    private final SourceFlowAnchorExtractionService anchorExtractionService;
    private final FlowAwareSourceDiscoveryService discoveryService;
    private final SourceCandidateGitHistoryService gitHistoryService;
    private final SourceReasoningContextBuilder contextBuilder;
    private final CompanySourceReasoningService companyReasoningService;
    private final ObjectMapper objectMapper;
    private final ReplayFixProperties properties;

    public SourceSuspectChangeAnalysisService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            SuspectSignalExtractionService signalExtractionService,
            SourceFlowAnchorExtractionService anchorExtractionService,
            FlowAwareSourceDiscoveryService discoveryService,
            SourceCandidateGitHistoryService gitHistoryService,
            SourceReasoningContextBuilder contextBuilder,
            CompanySourceReasoningService companyReasoningService,
            ObjectMapper objectMapper,
            ReplayFixProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.signalExtractionService = signalExtractionService;
        this.anchorExtractionService = anchorExtractionService;
        this.discoveryService = discoveryService;
        this.gitHistoryService = gitHistoryService;
        this.contextBuilder = contextBuilder;
        this.companyReasoningService = companyReasoningService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public SourceSuspectChangeAnalysisResponse analyze(
            UUID caseId,
            int lookbackDays,
            int maxCandidates,
            int maxCommitsPerFile,
            boolean includeDiffSnippets,
            boolean useCompanyLlm
    ) {
        List<String> warnings = new ArrayList<>();
        ReplayCaseEntity replayCase = defaultCase(caseId);
        RepositoryContext repositoryContext =
                new RepositoryContext("", "", "test2");
        List<SourceFlowAnchor> anchors = List.of();
        FlowAwareSourceDiscoveryService.DiscoveryResult discovery =
                emptyDiscovery();
        SourceCandidateGitHistoryService.HistoryResult history =
                new SourceCandidateGitHistoryService.HistoryResult(
                        List.of(),
                        List.of(),
                        List.of()
                );
        SourceReasoningContext context =
                emptyContext(replayCase, "", "test2", anchors);

        try {
            try {
                replayCase = caseRepository.findById(caseId)
                        .orElseGet(() -> defaultCase(caseId));
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis case lookup failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
            }

            SuspectSignalExtractionResponse signals = null;
            try {
                signals = signalExtractionService.extract(caseId, false);
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis signal extraction failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
            }

            try {
                repositoryContext = repositoryContext(
                        caseId,
                        signals == null ? "" : signals.repository(),
                        signals == null ? "" : signals.branch(),
                        replayCase
                );
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis repository context failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
                repositoryContext = new RepositoryContext(
                        signals == null ? "" : firstNonBlank(signals.repository(), ""),
                        repositorySlug(signals == null ? "" : signals.repository()),
                        firstNonBlank(replayCase.getSourceBranch(), "test2")
                );
            }

            try {
                anchors = anchorExtractionService.extract(
                        signals == null ? List.of() : signals.signals()
                );
                if (anchors.isEmpty()) {
                    warnings.add(NO_FLOW_ANCHOR_FOUND);
                    if (signals != null && !signals.signals().isEmpty()) {
                        warnings.add(ONLY_GENERIC_MATCHES_FOUND);
                    }
                }
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis anchor extraction failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
                anchors = List.of();
            }

            Optional<Path> workspace = Optional.empty();
            try {
                workspace = locateWorkspace(caseId, repositoryContext);
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis workspace resolution failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
            }

            if (workspace.isEmpty()) {
                warnings.add(SOURCE_WORKSPACE_NOT_FOUND);
                context = emptyContext(
                        replayCase,
                        repositoryContext.repository(),
                        repositoryContext.branch(),
                        anchors
                );
                return response(
                        replayCase,
                        repositoryContext,
                        lookbackDays,
                        anchors,
                        discovery.candidateFlowChain(),
                        discovery.candidateFiles(),
                        discovery.candidateMethods(),
                        history.recentCommits(),
                        context,
                        false,
                        List.of(),
                        warnings,
                        "DETERMINISTIC_ONLY"
                );
            }

            try {
                discovery = discoveryService.discover(
                        workspace.get(),
                        anchors,
                        Math.max(1, maxCandidates)
                );
                if (discovery.candidateFlowChain()
                        .stream()
                        .noneMatch(item -> "CONTROLLER".equals(item.layer()))) {
                    warnings.add(NO_ENDPOINT_MATCH_FOUND);
                }
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis discovery failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_DISCOVERY_FAILED);
                discovery = emptyDiscovery();
            }

            try {
                history = gitHistoryService.collect(
                        workspace.get(),
                        discovery.candidateFiles(),
                        discovery.javaFiles(),
                        Math.max(1, lookbackDays),
                        Math.max(1, maxCommitsPerFile),
                        includeDiffSnippets
                );
                warnings.addAll(history.warnings());
                if (history.recentCommits().isEmpty()) {
                    warnings.add(NO_RECENT_COMMITS_FOUND);
                }
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis git history failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_GIT_HISTORY_FAILED);
                history = new SourceCandidateGitHistoryService.HistoryResult(
                        List.of(),
                        List.of(),
                        List.of()
                );
            }

            try {
                SourceReasoningContextBuilder.ContextBuildResult contextBuild =
                        contextBuilder.build(
                        replayCase,
                        repositoryContext.repository(),
                        repositoryContext.branch(),
                        anchors,
                        discovery.candidateFlowChain(),
                        discovery.candidateMethods(),
                        history.recentCommits(),
                        history.diffSnippets()
                );
                warnings.addAll(contextBuild.warnings());
                context = contextBuild.context();
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis context build failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_REASONING_CONTEXT_FAILED);
                context = emptyContext(
                        replayCase,
                        repositoryContext.repository(),
                        repositoryContext.branch(),
                        anchors
                );
            }

            List<SourceSuspectChange> deterministicChanges =
                    deterministicSuspects(
                            discovery.candidateFlowChain(),
                            history.recentCommits()
                    );
            boolean llmUsed = false;
            List<SourceSuspectChange> suspectChanges = deterministicChanges;
            String analysisMode = "DETERMINISTIC_ONLY";

            if (useCompanyLlm) {
                try {
                    CompanySourceReasoningService.ReasoningResult reasoning =
                            companyReasoningService.reason(caseId, context);
                    warnings.addAll(reasoning.warnings());
                    llmUsed = reasoning.llmUsed();
                    if (reasoning.llmUsed()) {
                        analysisMode = "COMPANY_LLM";
                    }
                    if (reasoning.llmUsed()
                            && !reasoning.suspectChanges().isEmpty()) {
                        suspectChanges = reasoning.suspectChanges();
                    }
                } catch (Exception exception) {
                    log.warn(
                            "Source suspect change analysis company LLM failed for caseId={}",
                            caseId,
                            exception
                    );
                    warnings.add(CompanySourceReasoningService
                            .COMPANY_LLM_UNAVAILABLE);
                }
            }

            return response(
                    replayCase,
                    repositoryContext,
                    lookbackDays,
                    anchors,
                    discovery.candidateFlowChain(),
                    discovery.candidateFiles(),
                    discovery.candidateMethods(),
                    history.recentCommits(),
                    context,
                    llmUsed,
                    suspectChanges,
                    warnings,
                    analysisMode
            );
        } catch (Exception exception) {
            log.warn(
                    "Source suspect change analysis failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(SOURCE_CHANGE_ANALYSIS_FAILED);
            return response(
                    replayCase,
                    repositoryContext,
                    lookbackDays,
                    anchors,
                    discovery.candidateFlowChain(),
                    discovery.candidateFiles(),
                    discovery.candidateMethods(),
                    history.recentCommits(),
                    context,
                    false,
                    List.of(),
                    warnings,
                    "DETERMINISTIC_ONLY"
            );
        }
    }

    private SourceSuspectChangeAnalysisResponse response(
            ReplayCaseEntity replayCase,
            RepositoryContext repositoryContext,
            int lookbackDays,
            List<SourceFlowAnchor> anchors,
            List<SourceCandidateFlowChainItem> chain,
            List<String> candidateFiles,
            List<com.etiya.replayfix.model.SourceCandidateMethod> candidateMethods,
            List<SourceRecentCommit> recentCommits,
            SourceReasoningContext context,
            boolean llmUsed,
            List<SourceSuspectChange> suspectChanges,
            List<String> warnings,
            String analysisMode
    ) {
        List<String> uniqueWarnings = List.copyOf(new LinkedHashSet<>(warnings));
        return new SourceSuspectChangeAnalysisResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                repositoryContext.repository(),
                repositoryContext.branch(),
                replayCase.getSourceCommit(),
                lookbackDays,
                anchors,
                chain,
                candidateFiles,
                candidateMethods,
                recentCommits,
                context,
                llmUsed,
                suspectChanges,
                "HYPOTHESIS",
                suspectChanges.stream()
                        .mapToDouble(SourceSuspectChange::confidence)
                        .max()
                        .orElse(0.0),
                uniqueWarnings,
                analysisMode,
                !uniqueWarnings.isEmpty()
        );
    }

    private ReplayCaseEntity defaultCase(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("");
        replayCase.setTargetKey("backend");
        replayCase.setSourceBranch("test2");
        return replayCase;
    }

    private FlowAwareSourceDiscoveryService.DiscoveryResult emptyDiscovery() {
        return new FlowAwareSourceDiscoveryService.DiscoveryResult(
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    private List<SourceSuspectChange> deterministicSuspects(
            List<SourceCandidateFlowChainItem> chain,
            List<SourceRecentCommit> commits
    ) {
        Map<String, List<SourceRecentCommit>> commitsByFile =
                commits.stream().collect(
                        java.util.stream.Collectors.groupingBy(
                                commit -> commit.changedFiles().stream()
                                        .filter(file -> chain.stream()
                                                .anyMatch(item -> item.file().equals(file)))
                                        .findFirst()
                                        .orElse("")
                        )
                );

        return chain.stream()
                .map(item -> {
                    List<SourceRecentCommit> fileCommits =
                            commitsByFile.getOrDefault(item.file(), List.of());
                    return new SourceSuspectChange(
                            item.file(),
                            item.className(),
                            item.methodName(),
                            item.layer(),
                            String.join(", ", item.relatedSignals()),
                            item.relatedSignals(),
                            fileCommits.size(),
                            fileCommits,
                            item.reason(),
                            fileCommits.isEmpty() ? 0.25 : 0.45,
                            "HYPOTHESIS",
                            List.of()
                    );
                })
                .toList();
    }

    private Optional<Path> locateWorkspace(
            UUID caseId,
            RepositoryContext repositoryContext
    ) {
        Path caseWorkspace = Path.of(properties.getWorkspaceDir(), caseId.toString())
                .toAbsolutePath()
                .normalize();
        List<Path> candidates = new ArrayList<>();
        if (repositoryContext.repositorySlug() != null
                && !repositoryContext.repositorySlug().isBlank()) {
            candidates.add(caseWorkspace.resolve("repositories")
                    .resolve(repositoryContext.repositorySlug())
                    .normalize());
        }
        candidates.add(caseWorkspace.resolve("repository").normalize());
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst();
    }

    private RepositoryContext repositoryContext(
            UUID caseId,
            String fallbackRepository,
            String fallbackBranch,
            ReplayCaseEntity replayCase
    ) {
        JsonNode json = latestEvidence(caseId, EvidenceType.REPOSITORY_RESOLUTION)
                .flatMap(this::readJson)
                .orElse(null);

        String projectKey = json == null
                ? null
                : findText(json, "projectKey").orElse(null);
        String slug = json == null
                ? repositorySlug(fallbackRepository)
                : findText(json, "repositorySlug", "primaryRepositorySlug", "slug")
                .orElse(repositorySlug(fallbackRepository));
        String branch = firstNonBlank(
                fallbackBranch,
                json == null ? null : findText(json, "sourceBranch", "branch",
                        "targetBranch", "defaultBranch").orElse(null),
                replayCase.getSourceBranch(),
                "backend".equalsIgnoreCase(slug) ? "test2" : null,
                "test2"
        );
        String repository = firstNonBlank(
                fallbackRepository,
                projectKey == null || slug == null ? null : projectKey + "/" + slug,
                slug
        );
        return new RepositoryContext(repository, slug, branch);
    }

    private SourceReasoningContext emptyContext(
            ReplayCaseEntity replayCase,
            String repository,
            String branch,
            List<SourceFlowAnchor> anchors
    ) {
        return new SourceReasoningContext(
                Map.of(
                        "caseId", replayCase.getId().toString(),
                        "jiraKey", safeString(replayCase.getJiraKey()),
                        "repository", safeString(repository),
                        "branch", safeString(branch)
                ),
                Map.of("jiraKey", safeString(replayCase.getJiraKey())),
                "",
                anchors,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("SOURCE_WORKSPACE"),
                List.of("Bounded source reasoning context was not built.")
        );
    }

    private Optional<EvidenceEntity> latestEvidence(UUID caseId, EvidenceType type) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private Optional<JsonNode> readJson(EvidenceEntity evidence) {
        String content = evidence.getContentText() == null
                ? evidence.getBody()
                : evidence.getContentText();
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    private String repositorySlug(String repository) {
        if (repository == null || repository.isBlank()) {
            return "";
        }
        int slash = repository.lastIndexOf('/');
        return slash >= 0 ? repository.substring(slash + 1) : repository;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private record RepositoryContext(
            String repository,
            String repositorySlug,
            String branch
    ) {
    }
}

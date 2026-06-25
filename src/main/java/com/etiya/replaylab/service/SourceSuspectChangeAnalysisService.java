package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.SourceCandidateFlowChainItem;
import com.etiya.replaylab.model.SourceCandidateMethod;
import com.etiya.replaylab.model.SourceFlowAnchor;
import com.etiya.replaylab.model.SourceLastCommitDiagnostic;
import com.etiya.replaylab.model.SourceRecentCommit;
import com.etiya.replaylab.model.SourceReasoningContext;
import com.etiya.replaylab.model.SourceSuspectChange;
import com.etiya.replaylab.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replaylab.model.SuspectSignalExtractionResponse;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SourceSuspectChangeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(
            SourceSuspectChangeAnalysisService.class
    );

    public static final String SOURCE_CHANGE_ANALYSIS_FAILED =
            "SOURCE_CHANGE_ANALYSIS_FAILED";
    public static final String SOURCE_CHANGE_ANALYSIS_TIMEOUT =
            "SOURCE_CHANGE_ANALYSIS_TIMEOUT";
    public static final String SOURCE_DISCOVERY_FAILED =
            "SOURCE_DISCOVERY_FAILED";
    public static final String SOURCE_DISCOVERY_TIMEOUT =
            "SOURCE_DISCOVERY_TIMEOUT";
    public static final String SOURCE_GIT_HISTORY_FAILED =
            "SOURCE_GIT_HISTORY_FAILED";
    public static final String SOURCE_GIT_HISTORY_TIMEOUT =
            "SOURCE_GIT_HISTORY_TIMEOUT";
    public static final String SOURCE_REASONING_CONTEXT_FAILED =
            "SOURCE_REASONING_CONTEXT_FAILED";
    public static final String SOURCE_WORKSPACE_NOT_FOUND =
            "SOURCE_WORKSPACE_NOT_FOUND";
    public static final String NO_FLOW_ANCHOR_FOUND =
            "NO_FLOW_ANCHOR_FOUND";
    public static final String NO_ENDPOINT_MATCH_FOUND =
            "NO_ENDPOINT_MATCH_FOUND";
    public static final String ENDPOINT_ANCHOR_UNMATCHED =
            "ENDPOINT_ANCHOR_UNMATCHED";
    public static final String NO_RECENT_COMMITS_FOUND =
            "NO_RECENT_COMMITS_FOUND";
    public static final String ONLY_GENERIC_MATCHES_FOUND =
            "ONLY_GENERIC_MATCHES_FOUND";
    public static final String COMPANY_LLM_TIMEOUT =
            CompanySourceReasoningService.COMPANY_LLM_TIMEOUT;
    public static final String COMPANY_LLM_CONTEXT_TRUNCATED =
            "COMPANY_LLM_CONTEXT_TRUNCATED";
    public static final String COMPANY_LLM_OUTPUT_TOKEN_LIMIT_CLAMPED =
            "COMPANY_LLM_OUTPUT_TOKEN_LIMIT_CLAMPED";
    private static final String LLM_CONTEXT_MINIMAL = "MINIMAL";
    private static final String LLM_CONTEXT_COMPACT = "COMPACT";
    private static final String LLM_CONTEXT_FULL = "FULL";
    private static final int DEFAULT_COMPANY_LLM_MAX_PROMPT_CHARS = 12_000;
    private static final int DEFAULT_COMPANY_LLM_MAX_OUTPUT_TOKENS = 500;
    private static final int MAX_COMPANY_LLM_OUTPUT_TOKENS = 3_000;

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final SuspectSignalExtractionService signalExtractionService;
    private final SourceFlowAnchorExtractionService anchorExtractionService;
    private final FlowAwareSourceDiscoveryService discoveryService;
    private final SourceCandidateGitHistoryService gitHistoryService;
    private final SourceReasoningContextBuilder contextBuilder;
    private final CompanySourceReasoningService companyReasoningService;
    private final ObjectMapper objectMapper;
    private final ReplayLabProperties properties;

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
            ReplayLabProperties properties
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
        return analyze(
                caseId,
                lookbackDays,
                maxCandidates,
                maxCommitsPerFile,
                includeDiffSnippets,
                useCompanyLlm,
                2_000,
                256,
                false,
                10,
                8,
                8,
                LLM_CONTEXT_COMPACT,
                DEFAULT_COMPANY_LLM_MAX_PROMPT_CHARS,
                DEFAULT_COMPANY_LLM_MAX_OUTPUT_TOKENS
        );
    }

    @Transactional(readOnly = true)
    public SourceSuspectChangeAnalysisResponse analyze(
            UUID caseId,
            int lookbackDays,
            int maxCandidates,
            int maxCommitsPerFile,
            boolean includeDiffSnippets,
            boolean useCompanyLlm,
            int maxScannedFiles,
            int maxFileSizeKb,
            boolean includeTests,
            int sourceDiscoveryTimeoutSeconds,
            int gitHistoryTimeoutSeconds
    ) {
        return analyze(
                caseId,
                lookbackDays,
                maxCandidates,
                maxCommitsPerFile,
                includeDiffSnippets,
                useCompanyLlm,
                maxScannedFiles,
                maxFileSizeKb,
                includeTests,
                sourceDiscoveryTimeoutSeconds,
                gitHistoryTimeoutSeconds,
                8,
                LLM_CONTEXT_COMPACT,
                DEFAULT_COMPANY_LLM_MAX_PROMPT_CHARS,
                DEFAULT_COMPANY_LLM_MAX_OUTPUT_TOKENS
        );
    }

    @Transactional(readOnly = true)
    public SourceSuspectChangeAnalysisResponse analyze(
            UUID caseId,
            int lookbackDays,
            int maxCandidates,
            int maxCommitsPerFile,
            boolean includeDiffSnippets,
            boolean useCompanyLlm,
            int maxScannedFiles,
            int maxFileSizeKb,
            boolean includeTests,
            int sourceDiscoveryTimeoutSeconds,
            int gitHistoryTimeoutSeconds,
            int companyLlmTimeoutSeconds
    ) {
        return analyze(
                caseId,
                lookbackDays,
                maxCandidates,
                maxCommitsPerFile,
                includeDiffSnippets,
                useCompanyLlm,
                maxScannedFiles,
                maxFileSizeKb,
                includeTests,
                sourceDiscoveryTimeoutSeconds,
                gitHistoryTimeoutSeconds,
                companyLlmTimeoutSeconds,
                LLM_CONTEXT_COMPACT,
                DEFAULT_COMPANY_LLM_MAX_PROMPT_CHARS,
                DEFAULT_COMPANY_LLM_MAX_OUTPUT_TOKENS
        );
    }

    @Transactional(readOnly = true)
    public SourceSuspectChangeAnalysisResponse analyze(
            UUID caseId,
            int lookbackDays,
            int maxCandidates,
            int maxCommitsPerFile,
            boolean includeDiffSnippets,
            boolean useCompanyLlm,
            int maxScannedFiles,
            int maxFileSizeKb,
            boolean includeTests,
            int sourceDiscoveryTimeoutSeconds,
            int gitHistoryTimeoutSeconds,
            int companyLlmTimeoutSeconds,
            String llmContextMode,
            int companyLlmMaxPromptChars
    ) {
        return analyze(
                caseId,
                lookbackDays,
                maxCandidates,
                maxCommitsPerFile,
                includeDiffSnippets,
                useCompanyLlm,
                maxScannedFiles,
                maxFileSizeKb,
                includeTests,
                sourceDiscoveryTimeoutSeconds,
                gitHistoryTimeoutSeconds,
                companyLlmTimeoutSeconds,
                llmContextMode,
                companyLlmMaxPromptChars,
                DEFAULT_COMPANY_LLM_MAX_OUTPUT_TOKENS
        );
    }

    @Transactional(readOnly = true)
    public SourceSuspectChangeAnalysisResponse analyze(
            UUID caseId,
            int lookbackDays,
            int maxCandidates,
            int maxCommitsPerFile,
            boolean includeDiffSnippets,
            boolean useCompanyLlm,
            int maxScannedFiles,
            int maxFileSizeKb,
            boolean includeTests,
            int sourceDiscoveryTimeoutSeconds,
            int gitHistoryTimeoutSeconds,
            int companyLlmTimeoutSeconds,
            String llmContextMode,
            int companyLlmMaxPromptChars,
            int companyLlmMaxOutputTokens
    ) {
        List<String> warnings = new ArrayList<>();
        PhaseTimings timings = new PhaseTimings();
        String lastCompletedPhase = "";
        String currentPhaseOnTimeout = null;
        String normalizedLlmContextMode = normalizeLlmContextMode(llmContextMode);
        int normalizedMaxPromptChars = Math.max(1, companyLlmMaxPromptChars);
        int normalizedMaxOutputTokens = normalizeMaxOutputTokens(
                companyLlmMaxOutputTokens,
                warnings
        );
        CompanyLlmPhase companyLlmPhase = new CompanyLlmPhase(
                Math.max(1, companyLlmTimeoutSeconds),
                0L,
                useCompanyLlm ? "UNAVAILABLE" : "NOT_REQUESTED",
                0,
                normalizedLlmContextMode,
                normalizedMaxPromptChars,
                normalizedMaxOutputTokens,
                "",
                null,
                "",
                normalizedMaxOutputTokens,
                Map.of()
        );
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
            timings.start("evidenceResolution");
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
            timings.stop("evidenceResolution");
            lastCompletedPhase = "evidenceResolution";

            timings.start("flowAnchorExtraction");
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
            timings.stop("flowAnchorExtraction");
            lastCompletedPhase = "flowAnchorExtraction";

            Optional<Path> workspace = Optional.empty();
            timings.start("workspaceResolution");
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
            timings.stop("workspaceResolution");
            lastCompletedPhase = "workspaceResolution";

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
                        history.lastCommitDiagnostics(),
                        context,
                        false,
                        List.of(),
                        warnings,
                        "DETERMINISTIC_ONLY",
                        discovery,
                        companyLlmPhase,
                        timings,
                        lastCompletedPhase,
                        currentPhaseOnTimeout
                );
            }

            timings.start("sourceDiscovery");
            try {
                Path sourceWorkspace = workspace.get();
                List<SourceFlowAnchor> discoveryAnchors = anchors;
                discovery = runWithTimeout(
                        () -> discoveryService.discover(
                                sourceWorkspace,
                                discoveryAnchors,
                                Math.max(1, maxCandidates),
                                Math.max(1, maxScannedFiles),
                                Math.max(1, maxFileSizeKb),
                                includeTests
                        ),
                        Math.max(1, sourceDiscoveryTimeoutSeconds)
                );
                if (discovery.candidateFlowChain()
                        .stream()
                        .noneMatch(item -> "CONTROLLER".equals(item.layer()))) {
                    warnings.add(NO_ENDPOINT_MATCH_FOUND);
                }
                if (!discovery.unmatchedEndpointAnchors().isEmpty()
                        && discovery.matchedEndpointAnchors().isEmpty()) {
                    warnings.add(ENDPOINT_ANCHOR_UNMATCHED);
                }
                lastCompletedPhase = "sourceDiscovery";
            } catch (TimeoutException exception) {
                log.warn(
                        "Source suspect change analysis discovery timed out for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_DISCOVERY_TIMEOUT);
                currentPhaseOnTimeout = "sourceDiscovery";
            } catch (Exception exception) {
                log.warn(
                        "Source suspect change analysis discovery failed for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_DISCOVERY_FAILED);
                discovery = emptyDiscovery();
            } finally {
                timings.stop("sourceDiscovery");
            }

            timings.start("gitHistory");
            try {
                if (discovery.candidateFiles().isEmpty()) {
                    history = new SourceCandidateGitHistoryService.HistoryResult(
                            List.of(),
                            List.of(),
                            List.of()
                    );
                } else {
                    Path sourceWorkspace = workspace.get();
                    FlowAwareSourceDiscoveryService.DiscoveryResult
                            sourceDiscovery = discovery;
                    history = runWithTimeout(
                            () -> gitHistoryService.collect(
                                    sourceWorkspace,
                                    sourceDiscovery.candidateFiles(),
                                    sourceDiscovery.javaFiles(),
                                    Math.max(1, lookbackDays),
                                    Math.max(1, maxCommitsPerFile),
                                    includeDiffSnippets
                            ),
                            Math.max(1, gitHistoryTimeoutSeconds)
                    );
                }
                warnings.addAll(history.warnings());
                if (history.recentCommits().isEmpty()) {
                    warnings.add(NO_RECENT_COMMITS_FOUND);
                }
                lastCompletedPhase = "gitHistory";
            } catch (TimeoutException exception) {
                log.warn(
                        "Source suspect change analysis git history timed out for caseId={}",
                        caseId,
                        exception
                );
                warnings.add(SOURCE_GIT_HISTORY_TIMEOUT);
                currentPhaseOnTimeout = "gitHistory";
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
            } finally {
                timings.stop("gitHistory");
            }

            timings.start("contextBuild");
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
            } finally {
                timings.stop("contextBuild");
            }
            lastCompletedPhase = "contextBuild";

            List<SourceSuspectChange> deterministicChanges =
                    deterministicSuspects(
                            discovery.candidateFlowChain(),
                            history.recentCommits()
                    );
            boolean llmUsed = false;
            List<SourceSuspectChange> suspectChanges = deterministicChanges;
            String analysisMode = "DETERMINISTIC_ONLY";

            if (useCompanyLlm) {
                timings.start("companyLlm");
                try {
                    CompanyLlmContextPacket llmPacket = companyLlmContextPacket(
                            replayCase,
                            repositoryContext,
                            discovery,
                            history,
                            warnings,
                            context,
                            normalizedLlmContextMode,
                            normalizedMaxPromptChars
                    );
                    if (llmPacket.truncated()) {
                        warnings.add(COMPANY_LLM_CONTEXT_TRUNCATED);
                    }
                    String llmContextJson = llmPacket.contextJson();
                    companyLlmPhase = companyLlmPhase.withPrompt(
                            llmPacket.promptChars(),
                            sha256(llmContextJson)
                    );
                    CompanySourceReasoningService.ReasoningResult reasoning =
                            runWithTimeout(
                                    () -> companyReasoningService.reason(
                                            caseId,
                                            llmContextJson,
                                            normalizedMaxOutputTokens,
                                            normalizedLlmContextMode,
                                            Math.max(1, companyLlmTimeoutSeconds)
                                    ),
                                    Math.max(1, companyLlmTimeoutSeconds)
                            );
                    warnings.addAll(reasoning.warnings());
                    llmUsed = reasoning.llmUsed();
                    if (reasoning.llmUsed()) {
                        analysisMode = "COMPANY_LLM";
                    }
                    if (reasoning.llmUsed()
                            && !reasoning.suspectChanges().isEmpty()) {
                        suspectChanges = reasoning.suspectChanges();
                    }
                    companyLlmPhase = new CompanyLlmPhase(
                            Math.max(1, companyLlmTimeoutSeconds),
                            timings.currentElapsedMs(),
                            reasoning.llmUsed()
                                    ? "SUCCESS"
                                    : companyLlmStatus(reasoning.warnings()),
                            llmPacket.promptChars(),
                            normalizedLlmContextMode,
                            normalizedMaxPromptChars,
                            normalizedMaxOutputTokens,
                            sha256(llmContextJson),
                            reasoning.parseErrorCategory(),
                            reasoning.outputPreview(),
                            firstPositive(
                                    reasoning.effectiveOutputTokenLimit(),
                                    normalizedMaxOutputTokens
                            ),
                            reasoning.responseShape()
                    );
                } catch (TimeoutException exception) {
                    log.warn(
                            "Source suspect change analysis company LLM timed out for caseId={}",
                            caseId,
                            exception
                    );
                    warnings.add(CompanySourceReasoningService.COMPANY_LLM_TIMEOUT);
                    companyLlmPhase = new CompanyLlmPhase(
                            Math.max(1, companyLlmTimeoutSeconds),
                            timings.currentElapsedMs(),
                            "TIMEOUT",
                            companyLlmPhase.promptChars(),
                            normalizedLlmContextMode,
                            normalizedMaxPromptChars,
                            normalizedMaxOutputTokens,
                            companyLlmPhase.promptHash(),
                            null,
                            "",
                            normalizedMaxOutputTokens,
                            Map.of()
                    );
                } catch (Exception exception) {
                    log.warn(
                            "Source suspect change analysis company LLM failed for caseId={}",
                            caseId,
                            exception
                    );
                    warnings.add(CompanySourceReasoningService
                            .COMPANY_LLM_UNAVAILABLE);
                    companyLlmPhase = new CompanyLlmPhase(
                            Math.max(1, companyLlmTimeoutSeconds),
                            timings.currentElapsedMs(),
                            "ERROR",
                            companyLlmPhase.promptChars(),
                            normalizedLlmContextMode,
                            normalizedMaxPromptChars,
                            normalizedMaxOutputTokens,
                            companyLlmPhase.promptHash(),
                            "UNKNOWN",
                            "",
                            normalizedMaxOutputTokens,
                            Map.of()
                    );
                } finally {
                    timings.stop("companyLlm");
                }
                lastCompletedPhase = "companyLlm";
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
                    history.lastCommitDiagnostics(),
                    context,
                    llmUsed,
                    suspectChanges,
                    warnings,
                    analysisMode,
                    discovery,
                    companyLlmPhase,
                    timings,
                    lastCompletedPhase,
                    currentPhaseOnTimeout
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
                    history.lastCommitDiagnostics(),
                    context,
                    false,
                    List.of(),
                    warnings,
                    "DETERMINISTIC_ONLY",
                    discovery,
                    companyLlmPhase,
                    timings,
                    lastCompletedPhase,
                    currentPhaseOnTimeout
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
            List<SourceCandidateMethod> candidateMethods,
            List<SourceRecentCommit> recentCommits,
            List<SourceLastCommitDiagnostic> lastCommitDiagnostics,
            SourceReasoningContext context,
            boolean llmUsed,
            List<SourceSuspectChange> suspectChanges,
            List<String> warnings,
            String analysisMode,
            FlowAwareSourceDiscoveryService.DiscoveryResult discovery,
            CompanyLlmPhase companyLlmPhase,
            PhaseTimings timings,
            String lastCompletedPhase,
            String currentPhaseOnTimeout
    ) {
        List<String> uniqueWarnings = List.copyOf(new LinkedHashSet<>(warnings));
        timings.finish();
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
                !uniqueWarnings.isEmpty(),
                timings.values(),
                lastCompletedPhase,
                currentPhaseOnTimeout,
                discovery.endpointSearchFileCount(),
                discovery.controllerCandidateCount(),
                discovery.endpointMatchAttempts(),
                discovery.matchedEndpointAnchors(),
                discovery.unmatchedEndpointAnchors(),
                discovery.discoveredControllerEndpoints(),
                discovery.serviceResolutionAttempts(),
                discovery.resolvedServiceTypes(),
                discovery.resolvedImplementationFiles(),
                discovery.unresolvedServiceCalls(),
                lastCommitDiagnostics,
                companyLlmPhase.timeoutSeconds(),
                companyLlmPhase.elapsedMs(),
                companyLlmPhase.status(),
                companyLlmPhase.promptChars(),
                companyLlmPhase.contextMode(),
                companyLlmPhase.maxPromptChars(),
                companyLlmPhase.outputTokenLimit(),
                companyLlmPhase.promptHash(),
                companyLlmPhase.parseErrorCategory(),
                companyLlmPhase.outputPreview(),
                companyLlmPhase.effectiveOutputTokenLimit(),
                companyLlmPhase.responseShape()
        );
    }

    private CompanyLlmContextPacket companyLlmContextPacket(
            ReplayCaseEntity replayCase,
            RepositoryContext repositoryContext,
            FlowAwareSourceDiscoveryService.DiscoveryResult discovery,
            SourceCandidateGitHistoryService.HistoryResult history,
            List<String> warnings,
            SourceReasoningContext fullContext,
            String llmContextMode,
            int maxPromptChars
    ) throws Exception {
        if (LLM_CONTEXT_MINIMAL.equals(llmContextMode)) {
            String minimalJson = minimalPacketJson(
                    replayCase,
                    discovery,
                    history,
                    warnings
            );
            if (minimalJson.length() <= maxPromptChars) {
                return new CompanyLlmContextPacket(
                        minimalJson,
                        minimalJson.length(),
                        false
                );
            }
            String fitted = fitJson(minimalJson, maxPromptChars);
            return new CompanyLlmContextPacket(fitted, fitted.length(), true);
        }
        if (LLM_CONTEXT_FULL.equals(llmContextMode)) {
            String fullJson = objectMapper.writeValueAsString(fullContext);
            if (fullJson.length() <= maxPromptChars) {
                return new CompanyLlmContextPacket(
                        fullJson,
                        fullJson.length(),
                        false
                );
            }
            String compacted = objectMapper.writeValueAsString(Map.of(
                    "contextMode", LLM_CONTEXT_FULL,
                    "truncated", true,
                    "sourceReasoningContextPreview",
                    truncate(fullJson, Math.max(200, maxPromptChars - 160)),
                    "instruction",
                    "Output status must remain HYPOTHESIS."
            ));
            String fitted = fitJson(compacted, maxPromptChars);
            return new CompanyLlmContextPacket(
                    fitted,
                    fitted.length(),
                    true
            );
        }

        String compactJson = compactPacketJson(
                replayCase,
                repositoryContext,
                discovery,
                history,
                warnings,
                900,
                900,
                500
        );
        if (compactJson.length() <= maxPromptChars) {
            return new CompanyLlmContextPacket(
                    compactJson,
                    compactJson.length(),
                    false
            );
        }

        String truncatedJson = compactPacketJson(
                replayCase,
                repositoryContext,
                discovery,
                history,
                warnings,
                140,
                160,
                120
        );
        if (truncatedJson.length() <= maxPromptChars) {
            return new CompanyLlmContextPacket(
                    truncatedJson,
                    truncatedJson.length(),
                    true
            );
        }

        String minimalJson = objectMapper.writeValueAsString(Map.of(
                "contextMode", LLM_CONTEXT_COMPACT,
                "truncated", true,
                "caseId", replayCase.getId().toString(),
                "jiraKey", safeString(replayCase.getJiraKey()),
                "matchedEndpointAnchors",
                discovery.matchedEndpointAnchors().stream().limit(3).toList(),
                "candidateFlowChain",
                discovery.candidateFlowChain().stream()
                        .limit(3)
                        .map(this::compactChainItem)
                        .toList(),
                "warnings",
                warnings.stream().limit(5).toList(),
                "instruction",
                "Return JSON only. Status must remain HYPOTHESIS."
        ));
        String fitted = fitJson(minimalJson, maxPromptChars);
        return new CompanyLlmContextPacket(fitted, fitted.length(), true);
    }

    private String compactPacketJson(
            ReplayCaseEntity replayCase,
            RepositoryContext repositoryContext,
            FlowAwareSourceDiscoveryService.DiscoveryResult discovery,
            SourceCandidateGitHistoryService.HistoryResult history,
            List<String> warnings,
            int summaryChars,
            int snippetChars,
            int commitMessageChars
    ) throws Exception {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("contextMode", LLM_CONTEXT_COMPACT);
        packet.put("caseId", replayCase.getId().toString());
        packet.put("jiraKey", safeString(replayCase.getJiraKey()));
        packet.put("repository", safeString(repositoryContext.repository()));
        packet.put("branch", safeString(repositoryContext.branch()));
        packet.put("matchedEndpointAnchors", discovery.matchedEndpointAnchors());
        packet.put("candidateFlowChain", discovery.candidateFlowChain().stream()
                .limit(3)
                .map(this::compactChainItem)
                .toList());
        packet.put("candidateMethods", discovery.candidateMethods().stream()
                .limit(3)
                .map(method -> compactMethod(method, snippetChars))
                .toList());
        packet.put("lastCommitDiagnostics", history.lastCommitDiagnostics()
                .stream()
                .limit(10)
                .map(diagnostic -> compactDiagnostic(
                        diagnostic,
                        commitMessageChars
                ))
                .toList());
        packet.put("warnings", warnings.stream().limit(20).toList());
        packet.put("summary", shortEvidenceSummary(
                replayCase.getId(),
                summaryChars
        ));
        packet.put("instruction", """
                Use only this compact packet. Return valid JSON only. Keep status HYPOTHESIS.
                Do not invent files, commits, logs, tests or confirmation.
                """.strip());
        return objectMapper.writeValueAsString(packet);
    }

    private String minimalPacketJson(
            ReplayCaseEntity replayCase,
            FlowAwareSourceDiscoveryService.DiscoveryResult discovery,
            SourceCandidateGitHistoryService.HistoryResult history,
            List<String> warnings
    ) throws Exception {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("contextMode", LLM_CONTEXT_MINIMAL);
        packet.put("caseId", replayCase.getId().toString());
        packet.put("jiraKey", safeString(replayCase.getJiraKey()));
        packet.put("matchedEndpointAnchors", discovery.matchedEndpointAnchors());
        packet.put("candidateFlowChain", discovery.candidateFlowChain().stream()
                .limit(2)
                .map(this::minimalChainItem)
                .toList());
        packet.put("candidateMethods", minimalMethods(
                discovery.candidateMethods()
        ));
        packet.put("lastCommitDiagnostics", minimalCommitSummary(
                history.lastCommitDiagnostics()
        ));
        packet.put("warnings", warnings.stream().limit(10).toList());
        packet.put("outputSchema", Map.of(
                "status", "HYPOTHESIS",
                "confidence", 0.0,
                "suspectReason", "",
                "recommendedNextAction", "",
                "facts", List.of(),
                "inferences", List.of(),
                "unknowns", List.of(),
                "warnings", List.of()
        ));
        return objectMapper.writeValueAsString(packet);
    }

    private Map<String, Object> minimalChainItem(
            SourceCandidateFlowChainItem item
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("layer", item.layer());
        value.put("className", item.className());
        value.put("methodName", item.methodName());
        value.put("file", item.file());
        return value;
    }

    private List<Map<String, Object>> minimalMethods(
            List<SourceCandidateMethod> methods
    ) {
        List<SourceCandidateMethod> selected = methods.stream()
                .limit(2)
                .toList();
        int remainingSnippetChars = 800;
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            SourceCandidateMethod method = selected.get(index);
            int remainingMethods = selected.size() - index;
            int maxSnippetChars = remainingMethods <= 0
                    ? remainingSnippetChars
                    : remainingSnippetChars / remainingMethods;
            String snippet = truncate(
                    sanitizeCompactText(method.snippet()),
                    Math.max(0, maxSnippetChars)
            );
            remainingSnippetChars = Math.max(
                    0,
                    remainingSnippetChars - snippet.length()
            );
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("file", method.file());
            value.put("className", method.className());
            value.put("methodName", method.methodName());
            value.put("snippet", snippet);
            values.add(value);
        }
        return values;
    }

    private Map<String, Object> minimalCommitSummary(
            List<SourceLastCommitDiagnostic> diagnostics
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", diagnostics.size());
        summary.put("items", diagnostics.stream()
                .limit(5)
                .map(diagnostic -> compactDiagnostic(diagnostic, 120))
                .toList());
        return summary;
    }

    private Map<String, Object> compactChainItem(
            SourceCandidateFlowChainItem item
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("layer", item.layer());
        value.put("file", item.file());
        value.put("className", item.className());
        value.put("methodName", item.methodName());
        value.put("relatedSignals", item.relatedSignals());
        value.put("reason", truncate(safeString(item.reason()), 300));
        value.put("status", item.status());
        return value;
    }

    private Map<String, Object> compactMethod(
            SourceCandidateMethod method,
            int snippetChars
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("file", method.file());
        value.put("className", method.className());
        value.put("methodName", method.methodName());
        value.put("startLine", method.startLine());
        value.put("endLine", method.endLine());
        value.put("relatedSignals", method.relatedSignals());
        value.put("snippet", truncate(
                sanitizeCompactText(method.snippet()),
                snippetChars
        ));
        return value;
    }

    private Map<String, Object> compactDiagnostic(
            SourceLastCommitDiagnostic diagnostic,
            int messageChars
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("file", diagnostic.file());
        value.put("shortSha", diagnostic.shortSha());
        value.put("author", diagnostic.author());
        value.put("date", diagnostic.date());
        value.put("message", truncate(
                sanitizeCompactText(diagnostic.message()),
                messageChars
        ));
        return value;
    }

    private Map<String, String> shortEvidenceSummary(
            UUID caseId,
            int maxChars
    ) {
        Map<String, String> summary = new LinkedHashMap<>();
        latestEvidenceText(caseId, EvidenceType.ROVO_RCA)
                .ifPresent(value -> summary.put(
                        "rovo",
                        truncate(sanitizeCompactText(value), maxChars)
                ));
        latestEvidenceText(caseId, EvidenceType.JIRA_ISSUE)
                .ifPresent(value -> summary.put(
                        "jira",
                        truncate(sanitizeCompactText(value), maxChars)
                ));
        return summary;
    }

    private Optional<String> latestEvidenceText(UUID caseId, EvidenceType type) {
        return latestEvidence(caseId, type)
                .map(evidence -> firstNonBlank(
                        evidence.getContentText(),
                        evidence.getBody()
                ))
                .filter(value -> !value.isBlank());
    }

    private String normalizeLlmContextMode(String value) {
        if (LLM_CONTEXT_MINIMAL.equalsIgnoreCase(safeString(value))) {
            return LLM_CONTEXT_MINIMAL;
        }
        if (LLM_CONTEXT_FULL.equalsIgnoreCase(safeString(value))) {
            return LLM_CONTEXT_FULL;
        }
        return LLM_CONTEXT_COMPACT;
    }

    private int normalizeMaxOutputTokens(
            int requestedTokens,
            List<String> warnings
    ) {
        if (requestedTokens > MAX_COMPANY_LLM_OUTPUT_TOKENS) {
            warnings.add(COMPANY_LLM_OUTPUT_TOKEN_LIMIT_CLAMPED);
            return MAX_COMPANY_LLM_OUTPUT_TOKENS;
        }
        return Math.max(1, requestedTokens);
    }

    private String sanitizeCompactText(String value) {
        return safeString(value)
                .replaceAll("(?i)\"reasoning_content\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"reasoning_content\":\"[OMITTED]\"")
                .replaceAll("(?i)reasoning_content\\s*[:=]\\s*[^\\s,;\"}]+", "reasoning_content=[OMITTED]")
                .replaceAll("(?i)(authorization|token|cookie|password|secret)\\s*[:=]\\s*[^\\s,;\"}]+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private String fitJson(String json, int maxChars) {
        if (json.length() <= maxChars) {
            return json;
        }
        String minimal = "{\"contextMode\":\"COMPACT\",\"truncated\":true,"
                + "\"instruction\":\"Output status must remain HYPOTHESIS.\"}";
        if (minimal.length() <= maxChars) {
            return minimal;
        }
        return "{}";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safeString(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception exception) {
            return Integer.toHexString(safeString(value).hashCode());
        }
    }

    private <T> T runWithTimeout(Callable<T> callable, int timeoutSeconds)
            throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(callable);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private String companyLlmStatus(List<String> warnings) {
        if (warnings.contains(CompanySourceReasoningService.COMPANY_LLM_TIMEOUT)) {
            return "TIMEOUT";
        }
        if (warnings.contains(CompanySourceReasoningService
                .COMPANY_LLM_INVALID_RESPONSE)) {
            return "ERROR";
        }
        if (warnings.contains(CompanySourceReasoningService
                .COMPANY_LLM_UNAVAILABLE)) {
            return "UNAVAILABLE";
        }
        return "UNAVAILABLE";
    }

    private int firstPositive(int first, int second) {
        return first > 0 ? first : Math.max(0, second);
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

    private static final class PhaseTimings {
        private static final List<String> PHASES = List.of(
                "evidenceResolution",
                "flowAnchorExtraction",
                "workspaceResolution",
                "sourceDiscovery",
                "gitHistory",
                "contextBuild",
                "companyLlm",
                "total"
        );

        private final long totalStartedAt = System.nanoTime();
        private final Map<String, Long> values = new LinkedHashMap<>();
        private String activePhase;
        private long activeStartedAt;

        private PhaseTimings() {
            PHASES.forEach(phase -> values.put(phase, 0L));
        }

        private void start(String phase) {
            stopActive();
            activePhase = phase;
            activeStartedAt = System.nanoTime();
        }

        private void stop(String phase) {
            if (!phase.equals(activePhase)) {
                return;
            }
            values.put(phase, elapsedMs(activeStartedAt));
            activePhase = null;
        }

        private void finish() {
            stopActive();
            values.put("total", elapsedMs(totalStartedAt));
        }

        private Map<String, Long> values() {
            return Map.copyOf(values);
        }

        private void stopActive() {
            if (activePhase != null) {
                values.put(activePhase, elapsedMs(activeStartedAt));
                activePhase = null;
            }
        }

        private long elapsedMs(long startedAt) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        }

        private long currentElapsedMs() {
            if (activePhase == null) {
                return 0L;
            }
            return elapsedMs(activeStartedAt);
        }
    }

    private record CompanyLlmPhase(
            int timeoutSeconds,
            long elapsedMs,
            String status,
            int promptChars,
            String contextMode,
            int maxPromptChars,
            int outputTokenLimit,
            String promptHash,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit,
            Map<String, Object> responseShape
    ) {
        private CompanyLlmPhase withPrompt(
                int promptChars,
                String promptHash
        ) {
            return new CompanyLlmPhase(
                    timeoutSeconds,
                    elapsedMs,
                    status,
                    promptChars,
                    contextMode,
                    maxPromptChars,
                    outputTokenLimit,
                    promptHash,
                    parseErrorCategory,
                    outputPreview,
                    effectiveOutputTokenLimit,
                    responseShape
            );
        }
    }

    private record CompanyLlmContextPacket(
            String contextJson,
            int promptChars,
            boolean truncated
    ) {
    }

    private record RepositoryContext(
            String repository,
            String repositorySlug,
            String branch
    ) {
    }
}

package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.service.SourceSuspectChangeAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class SourceSuspectChangeAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(
            SourceSuspectChangeAnalysisController.class
    );
    private static final Duration ENDPOINT_TIMEOUT = Duration.ofSeconds(30);

    private final SourceSuspectChangeAnalysisService service;

    public SourceSuspectChangeAnalysisController(
            SourceSuspectChangeAnalysisService service
    ) {
        this.service = service;
    }

    @GetMapping("/{caseId}/source/suspect-change-analysis")
    public Mono<SourceSuspectChangeAnalysisResponse> analyze(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "45") int lookbackDays,
            @RequestParam(defaultValue = "20") int maxCandidates,
            @RequestParam(defaultValue = "10") int maxCommitsPerFile,
            @RequestParam(defaultValue = "false") boolean includeDiffSnippets,
            @RequestParam(defaultValue = "false") boolean useCompanyLlm,
            @RequestParam(defaultValue = "2000") int maxScannedFiles,
            @RequestParam(defaultValue = "256") int maxFileSizeKb,
            @RequestParam(defaultValue = "false") boolean includeTests,
            @RequestParam(defaultValue = "10") int sourceDiscoveryTimeoutSeconds,
            @RequestParam(defaultValue = "8") int gitHistoryTimeoutSeconds,
            @RequestParam(defaultValue = "8") int companyLlmTimeoutSeconds,
            @RequestParam(defaultValue = "COMPACT") String llmContextMode,
            @RequestParam(defaultValue = "12000") int companyLlmMaxPromptChars,
            @RequestParam(defaultValue = "500") int companyLlmMaxOutputTokens
    ) {
        return Mono.fromCallable(() -> service.analyze(
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
                        companyLlmMaxOutputTokens
                ))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::jsonSafeResponse)
                .timeout(ENDPOINT_TIMEOUT)
                .onErrorResume(exception -> {
                    String endpointPath = "/api/v1/cases/"
                            + caseId
                            + "/source/suspect-change-analysis";
                    log.warn(
                            "Source suspect change analysis endpoint fallback endpointPath={} caseId={} exceptionClass={} exceptionMessage={}",
                            endpointPath,
                            caseId,
                            exception.getClass().getName(),
                            exception.getMessage(),
                            exception
                    );
                    return Mono.just(fallbackResponse(
                            caseId,
                            lookbackDays,
                            "endpoint"
                    ));
                });
    }

    private SourceSuspectChangeAnalysisResponse jsonSafeResponse(
            SourceSuspectChangeAnalysisResponse response
    ) {
        return new SourceSuspectChangeAnalysisResponse(
                response.caseId(),
                response.jiraKey(),
                response.repository(),
                response.branch(),
                response.incidentCommitSha(),
                response.lookbackDays(),
                response.flowAnchors(),
                response.candidateFlowChain(),
                response.candidateFiles(),
                response.candidateMethods(),
                response.recentCommits(),
                response.sourceReasoningContext(),
                response.llmUsed(),
                response.suspectChanges(),
                response.status(),
                response.confidence(),
                response.warnings(),
                response.analysisMode(),
                response.partial(),
                response.phaseTimingsMs(),
                response.lastCompletedPhase(),
                response.currentPhaseOnTimeout(),
                response.endpointSearchFileCount(),
                response.controllerCandidateCount(),
                response.endpointMatchAttempts(),
                response.matchedEndpointAnchors(),
                response.unmatchedEndpointAnchors(),
                response.discoveredControllerEndpoints(),
                response.serviceResolutionAttempts(),
                response.resolvedServiceTypes(),
                response.resolvedImplementationFiles(),
                response.unresolvedServiceCalls(),
                response.lastCommitDiagnostics(),
                response.companyLlmTimeoutSeconds(),
                response.companyLlmElapsedMs(),
                response.companyLlmStatus(),
                response.companyLlmPromptChars(),
                response.companyLlmContextMode(),
                response.companyLlmMaxPromptChars(),
                response.companyLlmOutputTokenLimit(),
                response.companyLlmPromptHash()
        );
    }

    private SourceSuspectChangeAnalysisResponse fallbackResponse(
            UUID caseId,
            int lookbackDays,
            String currentPhaseOnTimeout
    ) {
        return new SourceSuspectChangeAnalysisResponse(
                caseId,
                "",
                "",
                "test2",
                "",
                lookbackDays,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new SourceReasoningContext(
                        Map.of(
                                "caseId", caseId.toString(),
                                "branch", "test2"
                        ),
                        Map.of(),
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("SOURCE_CHANGE_ANALYSIS"),
                        List.of("Endpoint returned deterministic fallback response.")
                ),
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(SourceSuspectChangeAnalysisService
                        .SOURCE_CHANGE_ANALYSIS_FAILED),
                "DETERMINISTIC_ONLY",
                true,
                fallbackTimings(),
                "",
                currentPhaseOnTimeout,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0L,
                "NOT_REQUESTED",
                0,
                "COMPACT",
                12000,
                500,
                ""
        );
    }

    private Map<String, Long> fallbackTimings() {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("evidenceResolution", 0L);
        timings.put("flowAnchorExtraction", 0L);
        timings.put("workspaceResolution", 0L);
        timings.put("sourceDiscovery", 0L);
        timings.put("gitHistory", 0L);
        timings.put("contextBuild", 0L);
        timings.put("companyLlm", 0L);
        timings.put("total", ENDPOINT_TIMEOUT.toMillis());
        return timings;
    }
}

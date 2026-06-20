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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class SourceSuspectChangeAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(
            SourceSuspectChangeAnalysisController.class
    );

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
            @RequestParam(defaultValue = "false") boolean useCompanyLlm
    ) {
        return Mono.fromCallable(() -> service.analyze(
                        caseId,
                        lookbackDays,
                        maxCandidates,
                        maxCommitsPerFile,
                        includeDiffSnippets,
                        useCompanyLlm
                ))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    log.warn(
                            "Source suspect change analysis endpoint fallback for caseId={}",
                            caseId,
                            exception
                    );
                    return Mono.just(fallbackResponse(caseId, lookbackDays));
                });
    }

    private SourceSuspectChangeAnalysisResponse fallbackResponse(
            UUID caseId,
            int lookbackDays
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
                true
        );
    }
}

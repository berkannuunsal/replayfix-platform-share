package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.service.SourceSuspectChangeAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class SourceSuspectChangeAnalysisController {

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
                .subscribeOn(Schedulers.boundedElastic());
    }
}

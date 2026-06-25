package com.etiya.replaylab.api;

import com.etiya.replaylab.model.SourceSuspectScanResponse;
import com.etiya.replaylab.service.SourceSuspectScanService;
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
public class SourceSuspectScanController {

    private final SourceSuspectScanService service;

    public SourceSuspectScanController(SourceSuspectScanService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/source/suspect-scan")
    public Mono<SourceSuspectScanResponse> suspectScan(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "20") int maxFiles,
            @RequestParam(defaultValue = "5") int maxSnippetsPerFile,
            @RequestParam(defaultValue = "false") boolean includeWeak
    ) {
        return Mono.fromCallable(() -> service.scan(
                        caseId,
                        maxFiles,
                        maxSnippetsPerFile,
                        includeWeak
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

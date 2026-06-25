package com.etiya.replaylab.api;

import com.etiya.replaylab.integration.TempoClient;
import com.etiya.replaylab.model.TempoConnectivityResult;
import com.etiya.replaylab.model.TempoCaseTraceCollection;
import com.etiya.replaylab.service.TempoCaseTraceCollectionService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TempoIntegrationController {

    private final TempoClient tempoClient;
    private final TempoCaseTraceCollectionService collectionService;

    public TempoIntegrationController(
            TempoClient tempoClient,
            TempoCaseTraceCollectionService collectionService
    ) {
        this.tempoClient = tempoClient;
        this.collectionService = collectionService;
    }

    @GetMapping("/integrations/tempo/connectivity")
    public Mono<TempoConnectivityResult> checkConnectivity() {
        return Mono.fromCallable(tempoClient::connectivity)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/cases/{caseId}/collect-tempo-traces")
    public Mono<TempoCaseTraceCollection> collectTraces(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() -> collectionService.collect(caseId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

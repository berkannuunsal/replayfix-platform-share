package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.service.SuspectSignalExtractionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class SourceSuspectSignalController {

    private final SuspectSignalExtractionService service;

    public SourceSuspectSignalController(
            SuspectSignalExtractionService service
    ) {
        this.service = service;
    }

    @GetMapping("/{caseId}/source/suspect-signals")
    public Mono<SuspectSignalExtractionResponse> suspectSignals(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() -> service.extract(caseId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

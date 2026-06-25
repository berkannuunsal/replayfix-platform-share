package com.etiya.replaylab.api;

import com.etiya.replaylab.model.DeterministicRootCauseRefreshResult;
import com.etiya.replaylab.service.DeterministicRootCauseRefreshService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class DeterministicRootCauseRefreshController {

    private final DeterministicRootCauseRefreshService
            refreshService;

    public DeterministicRootCauseRefreshController(
            DeterministicRootCauseRefreshService refreshService
    ) {
        this.refreshService = refreshService;
    }

    @PostMapping("/{id}/refresh-deterministic-root-cause")
    public Mono<DeterministicRootCauseRefreshResult> refresh(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                refreshService.refresh(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}

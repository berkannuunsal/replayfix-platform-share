package com.etiya.replayfix.api;

import com.etiya.replayfix.model.AiInputBundleRefreshResult;
import com.etiya.replayfix.service.AiInputBundleRefreshService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class AiInputBundleRefreshController {

    private final AiInputBundleRefreshService
            refreshService;

    public AiInputBundleRefreshController(
            AiInputBundleRefreshService refreshService
    ) {
        this.refreshService =
                refreshService;
    }

    @PostMapping("/{id}/refresh-ai-input")
    public Mono<AiInputBundleRefreshResult> refresh(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                refreshService.refresh(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}

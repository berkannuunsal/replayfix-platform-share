package com.etiya.replayfix.api;

import com.etiya.replayfix.model.KubernetesJenkinsVersionCorrelation;
import com.etiya.replayfix.model.KubernetesRuntimeInventory;
import com.etiya.replayfix.service.KubernetesJenkinsVersionCorrelationService;
import com.etiya.replayfix.service.KubernetesRuntimeInventoryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class KubernetesRuntimeController {

    private final KubernetesRuntimeInventoryService inventoryService;
    private final KubernetesJenkinsVersionCorrelationService correlationService;

    public KubernetesRuntimeController(
            KubernetesRuntimeInventoryService inventoryService,
            KubernetesJenkinsVersionCorrelationService correlationService
    ) {
        this.inventoryService = inventoryService;
        this.correlationService = correlationService;
    }

    @PostMapping("/{caseId}/collect-kubernetes-runtime")
    public Mono<KubernetesRuntimeInventory> collectRuntime(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() -> inventoryService.collect(caseId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{caseId}/correlate-kubernetes-version")
    public Mono<KubernetesJenkinsVersionCorrelation> correlateVersion(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() -> correlationService.correlate(caseId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

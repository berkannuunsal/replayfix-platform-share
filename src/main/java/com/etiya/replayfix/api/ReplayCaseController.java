package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.CreateCaseRequest;
import com.etiya.replayfix.api.dto.ReplayCaseResponse;
import com.etiya.replayfix.api.dto.StepResponse;
import com.etiya.replayfix.service.ConfigValidationService;
import com.etiya.replayfix.service.ReplayCaseService;
import com.etiya.replayfix.service.ReplayOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1")
public class ReplayCaseController {

    private final ReplayCaseService caseService;
    private final ReplayOrchestrator orchestrator;
    private final ConfigValidationService configValidationService;

    public ReplayCaseController(
        ReplayCaseService caseService,
        ReplayOrchestrator orchestrator,
        ConfigValidationService configValidationService
    ) {
        this.caseService = caseService;
        this.orchestrator = orchestrator;
        this.configValidationService = configValidationService;
    }

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ReplayCaseResponse> create(
        @Valid @RequestBody CreateCaseRequest request
    ) {
        return blocking(() ->
            ReplayCaseResponse.from(caseService.create(request))
        );
    }

    @GetMapping("/cases/{id}")
    public Mono<ReplayCaseResponse> get(@PathVariable UUID id) {
        return blocking(() ->
            ReplayCaseResponse.from(caseService.get(id))
        );
    }

    @GetMapping("/cases")
    public Mono<List<ReplayCaseResponse>> list() {
        return blocking(() ->
            caseService.list()
                .stream()
                .map(ReplayCaseResponse::from)
                .toList()
        );
    }

    @PostMapping("/cases/{id}/collect-context")
    public Mono<StepResponse> collectContext(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.collectContext(id)
        );
    }

    @PostMapping("/cases/{id}/provision")
    public Mono<StepResponse> provision(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.provision(id)
        );
    }

    @PostMapping("/cases/{id}/replay")
    public Mono<StepResponse> replay(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.replay(id)
        );
    }

    @PostMapping("/cases/{id}/generate-test")
    public Mono<StepResponse> generateTest(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.generateTest(id)
        );
    }

    @PostMapping("/cases/{id}/generate-patch")
    public Mono<StepResponse> generatePatch(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.generatePatch(id)
        );
    }

    @PostMapping("/cases/{id}/publish-validate")
    public Mono<StepResponse> publishAndValidate(
        @PathVariable UUID id
    ) {
        return blocking(() ->
            orchestrator.publishAndValidate(id)
        );
    }

    @PostMapping("/cases/{id}/run-all")
    public Mono<List<StepResponse>> runAll(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.runAll(id)
        );
    }

    @DeleteMapping("/cases/{id}/environment")
    public Mono<StepResponse> cleanup(@PathVariable UUID id) {
        return blocking(() ->
            orchestrator.cleanup(id)
        );
    }

    @GetMapping("/config/status")
    public Mono<Map<String, Object>> configStatus() {
        return blocking(configValidationService::validate);
    }

    private <T> Mono<T> blocking(Supplier<T> operation) {
        return Mono.fromCallable(operation::get)
            .subscribeOn(Schedulers.boundedElastic());
    }
}

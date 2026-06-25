package com.etiya.replaylab.api;

import com.etiya.replaylab.integration.AiClient;
import com.etiya.replaylab.model.AiConnectionTestResult;
import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ai")
public class AiConnectivityController {

    private final AiClient aiClient;
    private final AiProviderClientFactory providerFactory;

    public AiConnectivityController(
            AiClient aiClient,
            AiProviderClientFactory providerFactory
    ) {
        this.aiClient = aiClient;
        this.providerFactory = providerFactory;
    }

    @GetMapping("/connectivity")
    public Mono<AiConnectivityResult> connectivity(
            @RequestParam(required = false) String modelProfile,
            @RequestParam(required = false) String modelName
    ) {
        return Mono.fromCallable(() -> {
            AiProviderClient provider = providerFactory.getProvider();
            return provider.connectivity(modelProfile, modelName);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<AiConnectivityResult> connectivity() {
        return connectivity(null, null);
    }

    @PostMapping("/test")
    public Mono<AiConnectionTestResult> test() {
        return Mono.fromCallable(
                aiClient::testConnection
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}

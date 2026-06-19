package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.AiClient;
import com.etiya.replayfix.model.AiConnectionTestResult;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public Mono<AiConnectivityResult> connectivity() {
        return Mono.fromCallable(() -> {
            AiProviderClient provider = providerFactory.getProvider();
            return provider.connectivity();
        }).subscribeOn(Schedulers.boundedElastic());
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

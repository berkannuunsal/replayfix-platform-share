package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.AiClient;
import com.etiya.replayfix.model.AiConnectionTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ai")
public class AiConnectivityController {

    private final AiClient aiClient;

    public AiConnectivityController(
            AiClient aiClient
    ) {
        this.aiClient = aiClient;
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

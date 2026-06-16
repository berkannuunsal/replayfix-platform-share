package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.ConfluenceClient;
import com.etiya.replayfix.model.ConfluenceConnectivityResult;
import com.etiya.replayfix.model.ConfluenceKnowledgeContext;
import com.etiya.replayfix.service.ConfluenceKnowledgeCollectionService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ConfluenceIntegrationController {

    private final ConfluenceClient confluenceClient;
    private final ConfluenceKnowledgeCollectionService collectionService;

    public ConfluenceIntegrationController(
            ConfluenceClient confluenceClient,
            ConfluenceKnowledgeCollectionService collectionService
    ) {
        this.confluenceClient = confluenceClient;
        this.collectionService = collectionService;
    }

    @GetMapping("/integrations/confluence/connectivity")
    public Mono<ConfluenceConnectivityResult> checkConnectivity() {
        return Mono.fromCallable(confluenceClient::connectivity)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/cases/{caseId}/collect-confluence-knowledge")
    public Mono<ConfluenceKnowledgeContext> collectKnowledge(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() -> collectionService.collect(caseId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

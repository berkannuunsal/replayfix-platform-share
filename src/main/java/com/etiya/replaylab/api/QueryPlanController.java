package com.etiya.replaylab.api;

import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.LokiQueryPlan;
import com.etiya.replaylab.service.IncidentSignalExtractor;
import com.etiya.replaylab.service.JiraAdfTextExtractor;
import com.etiya.replaylab.service.LokiQueryPlanner;
import com.etiya.replaylab.service.ReplayCaseService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class QueryPlanController {

    private final ReplayCaseService caseService;
    private final JiraClient jiraClient;
    private final JiraAdfTextExtractor adfTextExtractor;
    private final IncidentSignalExtractor signalExtractor;
    private final LokiQueryPlanner queryPlanner;

    public QueryPlanController(
        ReplayCaseService caseService,
        JiraClient jiraClient,
        JiraAdfTextExtractor adfTextExtractor,
        IncidentSignalExtractor signalExtractor,
        LokiQueryPlanner queryPlanner
    ) {
        this.caseService = caseService;
        this.jiraClient = jiraClient;
        this.adfTextExtractor = adfTextExtractor;
        this.signalExtractor = signalExtractor;
        this.queryPlanner = queryPlanner;
    }

    @GetMapping("/{id}/query-plan")
    public Mono<LokiQueryPlan> preview(
        @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {
                var replayCase = caseService.get(id);

                var jiraIssue = jiraClient.getIssue(
                    replayCase.getJiraKey()
                );

                String plainDescription =
                    adfTextExtractor.extract(
                        jiraIssue.description()
                    );

                var signals = signalExtractor.extract(
                    jiraIssue.summary()
                        + "\n"
                        + plainDescription
                );

                return queryPlanner.plan(
                    jiraIssue.key(),
                    jiraIssue.summary(),
                    plainDescription,
                    signals
                );
            })
            .subscribeOn(Schedulers.boundedElastic());
    }
}

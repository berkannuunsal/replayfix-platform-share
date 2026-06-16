package com.etiya.replayfix.api;

import com.etiya.replayfix.model.ApprovalDecisionRequest;
import com.etiya.replayfix.model.ApprovalRequestView;
import com.etiya.replayfix.model.CreateApprovalRequest;
import com.etiya.replayfix.service.ApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(
            ApprovalService approvalService
    ) {
        this.approvalService =
                approvalService;
    }

    @PostMapping(
            "/cases/{caseId}/approvals/regression-test-plan"
    )
    public Mono<ApprovalRequestView> requestApproval(
            @PathVariable UUID caseId,
            @RequestBody CreateApprovalRequest request
    ) {
        return Mono.fromCallable(() ->
                approvalService
                        .createRegressionTestPlanApproval(
                                caseId,
                                request.actor(),
                                request.comment()
                        )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @PostMapping(
            "/approvals/{approvalId}/approve"
    )
    public Mono<ApprovalRequestView> approve(
            @PathVariable UUID approvalId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        return Mono.fromCallable(() ->
                approvalService.approve(
                        approvalId,
                        request.actor(),
                        request.comment()
                )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @PostMapping(
            "/approvals/{approvalId}/reject"
    )
    public Mono<ApprovalRequestView> reject(
            @PathVariable UUID approvalId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        return Mono.fromCallable(() ->
                approvalService.reject(
                        approvalId,
                        request.actor(),
                        request.comment()
                )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @GetMapping(
            "/cases/{caseId}/approvals"
    )
    public Mono<List<ApprovalRequestView>> list(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() ->
                approvalService.list(caseId)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @PostMapping(
            "/cases/{caseId}/approvals/generated-test-execution"
    )
    public Mono<ApprovalRequestView> requestExecutionApproval(
            @PathVariable UUID caseId,
            @RequestBody CreateApprovalRequest request
    ) {
        return Mono.fromCallable(() ->
                approvalService
                        .createGeneratedTestExecutionApproval(
                                caseId,
                                request.actor(),
                                request.comment()
                        )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @PostMapping(
            "/cases/{caseId}/approvals/pattern-informed-test-source"
    )
    public Mono<ApprovalRequestView> requestPatternSourceApproval(
            @PathVariable UUID caseId,
            @RequestBody CreateApprovalRequest request
    ) {
        return Mono.fromCallable(() ->
                approvalService
                        .createPatternInformedTestSourceApproval(
                                caseId,
                                request.actor(),
                                request.comment()
                        )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}

package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeCandidateExtractionResponse;
import com.etiya.replaylab.api.dto.CodeChangeCandidateHydrationRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResponse;
import com.etiya.replaylab.service.CodeChangeCandidateExtractionService;
import com.etiya.replaylab.service.CodeChangeAdvisoryOrchestrationService;
import com.etiya.replaylab.service.CodeChangeAdvisoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class CodeChangeAdvisoryController {

    private final CodeChangeAdvisoryService advisoryService;
    private final CodeChangeAdvisoryOrchestrationService orchestrationService;
    private final CodeChangeCandidateExtractionService candidateExtractionService;

    public CodeChangeAdvisoryController(
            CodeChangeAdvisoryService advisoryService,
            CodeChangeAdvisoryOrchestrationService orchestrationService,
            CodeChangeCandidateExtractionService candidateExtractionService
    ) {
        this.advisoryService = advisoryService;
        this.orchestrationService = orchestrationService;
        this.candidateExtractionService = candidateExtractionService;
    }

    @PostMapping("/{caseId}/code-change-advisory")
    public CodeChangeAdvisoryResponse advise(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean useCompanyLlm,
            @RequestParam(defaultValue = "BACKEND_METHOD") String advisoryMode,
            @RequestParam(defaultValue = "60") int companyLlmTimeoutSeconds,
            @RequestParam(defaultValue = "12000") int maxSnippetChars,
            @RequestParam(required = false) String modelProfile,
            @RequestParam(required = false) String modelName,
            @RequestBody(required = false) CodeChangeAdvisoryRequest request
    ) {
        return advisoryService.advise(
                caseId,
                advisoryMode,
                useCompanyLlm,
                companyLlmTimeoutSeconds,
                maxSnippetChars,
                modelProfile,
                modelName,
                request
        );
    }

    @GetMapping("/{caseId}/code-change-advisory/summary")
    public CodeChangeAdvisoryEvaluationSummaryResponse summary(
            @PathVariable UUID caseId
    ) {
        return advisoryService.summary(caseId);
    }

    @GetMapping("/{caseId}/code-change-advisory/candidates")
    public CodeChangeCandidateExtractionResponse candidates(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "3") int maxCandidates,
            @RequestParam(defaultValue = "12000") int maxSnippetChars,
            @RequestParam(defaultValue = "false") boolean includeSnippetPreview
    ) {
        return candidateExtractionService.extract(
                caseId,
                maxCandidates,
                maxSnippetChars,
                includeSnippetPreview
        );
    }

    @PostMapping("/{caseId}/code-change-advisory/candidates/hydrate")
    public CodeChangeCandidateExtractionResponse hydrateCandidates(
            @PathVariable UUID caseId,
            @RequestBody(required = false)
            CodeChangeCandidateHydrationRequest request
    ) {
        CodeChangeCandidateHydrationRequest safeRequest = request == null
                ? new CodeChangeCandidateHydrationRequest(
                java.util.List.of(),
                false,
                12000
        )
                : request;
        return candidateExtractionService.hydrate(
                caseId,
                safeRequest.candidateHints(),
                safeRequest.candidateHints().isEmpty()
                        ? 1
                        : safeRequest.candidateHints().size(),
                safeRequest.maxSnippetChars() == null
                        ? 12000
                        : safeRequest.maxSnippetChars(),
                safeRequest.includeSnippetPreview() != null
                        && safeRequest.includeSnippetPreview()
        );
    }

    @PostMapping("/{caseId}/code-change-advisory/orchestrate")
    public CodeChangeAdvisoryOrchestrationResponse orchestrate(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean useCompanyLlm,
            @RequestParam(defaultValue = "3") int maxCandidates,
            @RequestParam(defaultValue = "60") int companyLlmTimeoutSeconds,
            @RequestParam(defaultValue = "12000") int maxSnippetChars,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) String modelProfile,
            @RequestParam(required = false) String modelName,
            @RequestBody(required = false)
            CodeChangeAdvisoryOrchestrationRequest request
    ) {
        return orchestrationService.orchestrate(
                caseId,
                useCompanyLlm,
                maxCandidates,
                companyLlmTimeoutSeconds,
                maxSnippetChars,
                dryRun,
                modelProfile,
                modelName,
                request
        );
    }
}

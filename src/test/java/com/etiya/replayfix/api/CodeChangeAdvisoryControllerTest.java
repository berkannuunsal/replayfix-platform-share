package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.CodeChangeCandidateExtractionResponse;
import com.etiya.replayfix.api.dto.CodeChangeCandidateSummary;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryOrchestrationResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replayfix.api.dto.RecommendedCodeChange;
import com.etiya.replayfix.service.CodeChangeCandidateExtractionService;
import com.etiya.replayfix.service.CodeChangeAdvisoryOrchestrationService;
import com.etiya.replayfix.service.CodeChangeAdvisoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodeChangeAdvisoryControllerTest {

    @Test
    void codeChangeAdvisoryEndpointReturnsResponse() throws Exception {
        UUID caseId = UUID.randomUUID();
        CodeChangeAdvisoryService service =
                mock(CodeChangeAdvisoryService.class);
        CodeChangeAdvisoryOrchestrationService orchestrationService =
                mock(CodeChangeAdvisoryOrchestrationService.class);
        CodeChangeCandidateExtractionService extractionService =
                mock(CodeChangeCandidateExtractionService.class);
        when(service.advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(false),
                eq(30),
                eq(5000),
                eq(null),
                eq(null),
                any()
        )).thenReturn(new CodeChangeAdvisoryResponse(
                caseId,
                "BACKEND_METHOD",
                false,
                "NOT_REQUESTED",
                "HYPOTHESIS",
                "0.0",
                new RecommendedCodeChange(
                        "OrderService.java",
                        "complete",
                        "ADVISORY_ONLY",
                        "Review method-level context.",
                        ""
                ),
                List.of("No replay execution yet."),
                List.of("CODE_SNIPPET_MISSING"),
                List.of("Add a focused unit test."),
                false,
                "FALLBACK_NOT_REQUESTED",
                Map.of(
                        "filePath",
                        "OrderService.java",
                        "codeSnippetChars",
                        0
                ),
                Instant.parse("2026-06-23T06:00:00Z")
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CodeChangeAdvisoryController(
                        service,
                        orchestrationService,
                        extractionService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/code-change-advisory",
                        caseId
                )
                        .param("useCompanyLlm", "false")
                        .param("advisoryMode", "BACKEND_METHOD")
                        .param("companyLlmTimeoutSeconds", "30")
                        .param("maxSnippetChars", "5000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "problemSummary": "Order status mismatch",
                                  "filePath": "OrderService.java",
                                  "methodName": "complete",
                                  "language": "JAVA",
                                  "codeSnippet": ""
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.advisoryMode").value("BACKEND_METHOD"))
                .andExpect(jsonPath("$.llmUsed").value(false))
                .andExpect(jsonPath("$.deterministicFallbackReason")
                        .value("FALLBACK_NOT_REQUESTED"))
                .andExpect(jsonPath("$.safePromptSummary.filePath")
                        .value("OrderService.java"));

        verify(service).advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(false),
                eq(30),
                eq(5000),
                eq(null),
                eq(null),
                any()
        );
    }

    @Test
    void codeChangeAdvisoryEndpointRejectsPlainModelName()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        CodeChangeAdvisoryService service =
                mock(CodeChangeAdvisoryService.class);
        CodeChangeAdvisoryOrchestrationService orchestrationService =
                mock(CodeChangeAdvisoryOrchestrationService.class);
        CodeChangeCandidateExtractionService extractionService =
                mock(CodeChangeCandidateExtractionService.class);
        doThrow(new IllegalArgumentException(
                "MODEL_NAME_MUST_USE_OPENAI_PREFIX"
        )).when(service).advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(true),
                eq(60),
                eq(12000),
                eq(null),
                eq("gpt-3.5-turbo"),
                any()
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CodeChangeAdvisoryController(
                        service,
                        orchestrationService,
                        extractionService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/code-change-advisory",
                        caseId
                )
                        .param("advisoryMode", "BACKEND_METHOD")
                        .param("modelName", "gpt-3.5-turbo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filePath": "OrderService.java",
                                  "methodName": "complete",
                                  "language": "JAVA",
                                  "codeSnippet": "void complete() {}"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("MODEL_NAME_MUST_USE_OPENAI_PREFIX"));
    }

    @Test
    void codeChangeAdvisorySummaryEndpointReturnsEvaluation()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        CodeChangeAdvisoryService service =
                mock(CodeChangeAdvisoryService.class);
        CodeChangeAdvisoryOrchestrationService orchestrationService =
                mock(CodeChangeAdvisoryOrchestrationService.class);
        CodeChangeCandidateExtractionService extractionService =
                mock(CodeChangeCandidateExtractionService.class);
        when(service.summary(eq(caseId))).thenReturn(
                new CodeChangeAdvisoryEvaluationSummaryResponse(
                        caseId,
                        "FIZZMS-10228",
                        "bss-monolith",
                        2,
                        new CodeChangeAdvisoryResultSummary(
                                UUID.randomUUID(),
                                "BACKEND_METHOD",
                                "OrderService.java",
                                "OrderService",
                                "complete",
                                "JAVA",
                                true,
                                "SUCCESS",
                                "HYPOTHESIS",
                                "MEDIUM",
                                "CONDITIONAL_MAPPING",
                                false,
                                0,
                                0,
                                1,
                                new RecommendedCodeChange(
                                        "OrderService.java",
                                        "complete",
                                        "CONDITIONAL_MAPPING",
                                        "Map complete status.",
                                        "pseudo patch"
                                ),
                                List.of(),
                                List.of(),
                                List.of("Add a focused unit test."),
                                "",
                                Map.ofEntries(
                                        Map.entry("provider",
                                                "LITELLM_OPENAI_COMPATIBLE"),
                                        Map.entry("modelProfile",
                                                "CODE_ADVISORY"),
                                        Map.entry("effectiveModelName",
                                                "openai/gpt-4o-mini"),
                                        Map.entry("effectiveTimeoutSeconds",
                                                90),
                                        Map.entry("effectiveMaxPromptChars",
                                                12000),
                                        Map.entry("effectiveMaxOutputTokens",
                                                3000),
                                        Map.entry("budgetTrackingEnabled",
                                                true),
                                        Map.entry("budgetPeriod",
                                                "WEEKLY"),
                                        Map.entry("weeklyBudgetUsd",
                                                200.0),
                                        Map.entry("estimatedUsageAvailable",
                                                true),
                                        Map.entry("promptTokenCount",
                                                10),
                                        Map.entry("completionTokenCount",
                                                20),
                                        Map.entry("totalTokenCount",
                                                30)
                                ),
                                Instant.parse("2026-06-23T06:00:00Z")
                        ),
                        null,
                        null,
                        null,
                        0.66,
                        1,
                        0,
                        0,
                        "ADVISORY_READY",
                        Instant.parse("2026-06-23T06:00:00Z")
                )
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CodeChangeAdvisoryController(
                        service,
                        orchestrationService,
                        extractionService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/code-change-advisory/summary",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.advisoryGeneratedCount").value(2))
                .andExpect(jsonPath("$.averageConfidence").value(0.66))
                .andExpect(jsonPath("$.caseAdvisoryStatus")
                        .value("ADVISORY_READY"))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.provider")
                        .value("LITELLM_OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.modelProfile")
                        .value("CODE_ADVISORY"))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.effectiveModelName")
                        .value("openai/gpt-4o-mini"))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.budgetPeriod")
                        .value("WEEKLY"))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.weeklyBudgetUsd")
                        .value(200.0))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.totalTokenCount")
                        .value(30))
                .andExpect(jsonPath("$.latestBackendMethodAdvisory.safePromptSummary.monthlyBudgetUsd")
                        .doesNotExist());

        verify(service).summary(caseId);
    }

    @Test
    void codeChangeAdvisoryOrchestrationEndpointReturnsSummary()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        CodeChangeAdvisoryService service =
                mock(CodeChangeAdvisoryService.class);
        CodeChangeAdvisoryOrchestrationService orchestrationService =
                mock(CodeChangeAdvisoryOrchestrationService.class);
        CodeChangeCandidateExtractionService extractionService =
                mock(CodeChangeCandidateExtractionService.class);
        when(orchestrationService.orchestrate(
                eq(caseId),
                eq(false),
                eq(2),
                eq(30),
                eq(5000),
                eq(true),
                eq(null),
                eq(null),
                any()
        )).thenReturn(new CodeChangeAdvisoryOrchestrationResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "COMPLETED",
                true,
                1,
                1,
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of("Review advisory evaluation summary before any patch planning"),
                List.of(),
                null,
                Instant.parse("2026-06-23T06:00:00Z")
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CodeChangeAdvisoryController(
                        service,
                        orchestrationService,
                        extractionService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/code-change-advisory/orchestrate",
                        caseId
                )
                        .param("useCompanyLlm", "false")
                        .param("maxCandidates", "2")
                        .param("companyLlmTimeoutSeconds", "30")
                        .param("maxSnippetChars", "5000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "problemSummary": "Order status mismatch",
                                  "candidateHints": [
                                    {
                                      "filePath": "OrderService.java",
                                      "methodName": "complete",
                                      "language": "JAVA",
                                      "codeSnippet": "void complete() {}"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.orchestrationStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.processedCandidateCount").value(1))
                .andExpect(jsonPath("$.advisoryResultCount").value(1));

        verify(orchestrationService).orchestrate(
                eq(caseId),
                eq(false),
                eq(2),
                eq(30),
                eq(5000),
                eq(true),
                eq(null),
                eq(null),
                any()
        );
    }

    @Test
    void codeChangeAdvisoryCandidatesEndpointReturnsSafeSummary()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        CodeChangeAdvisoryService service =
                mock(CodeChangeAdvisoryService.class);
        CodeChangeAdvisoryOrchestrationService orchestrationService =
                mock(CodeChangeAdvisoryOrchestrationService.class);
        CodeChangeCandidateExtractionService extractionService =
                mock(CodeChangeCandidateExtractionService.class);
        when(extractionService.extract(
                eq(caseId),
                eq(2),
                eq(5000),
                eq(true)
        )).thenReturn(new CodeChangeCandidateExtractionResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                1,
                List.of(new CodeChangeCandidateSummary(
                        "OrderService.java",
                        "OrderService",
                        "complete",
                        "JAVA",
                        true,
                        42,
                        "public void complete() {}",
                        List.of(),
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of()
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CodeChangeAdvisoryController(
                        service,
                        orchestrationService,
                        extractionService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/code-change-advisory/candidates",
                        caseId
                )
                        .param("maxCandidates", "2")
                        .param("maxSnippetChars", "5000")
                        .param("includeSnippetPreview", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].snippetAvailable")
                        .value(true))
                .andExpect(jsonPath("$.candidates[0].snippetPreview")
                        .value("public void complete() {}"));

        verify(extractionService).extract(caseId, 2, 5000, true);
    }
}

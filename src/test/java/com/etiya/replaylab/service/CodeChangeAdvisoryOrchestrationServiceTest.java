package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryCandidateHint;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replaylab.api.dto.RecommendedCodeChange;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayInputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeChangeAdvisoryOrchestrationServiceTest {

    private ReplayCaseRepository caseRepository;
    private ReplayInputRepository replayInputRepository;
    private EvidenceRepository evidenceRepository;
    private CodeChangeAdvisoryService advisoryService;
    private CodeChangeCandidateExtractionService candidateExtractionService;
    private CodeChangeAdvisoryOrchestrationService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        replayInputRepository = mock(ReplayInputRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        advisoryService = mock(CodeChangeAdvisoryService.class);
        candidateExtractionService = mock(CodeChangeCandidateExtractionService.class);
        service = new CodeChangeAdvisoryOrchestrationService(
                caseRepository,
                replayInputRepository,
                evidenceRepository,
                advisoryService,
                candidateExtractionService,
                new ObjectMapper().findAndRegisterModules()
        );
        caseId = UUID.randomUUID();
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(replayInputRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(Optional.empty());
        when(evidenceRepository.findByCaseIdAndEvidenceType(eq(caseId), any(EvidenceType.class)))
                .thenReturn(List.of());
        when(candidateExtractionService.extractCandidateHints(
                eq(caseId),
                anyInt(),
                anyInt()
        )).thenReturn(List.of());
        when(advisoryService.summary(caseId)).thenReturn(summary(0));
        when(advisoryService.advise(
                eq(caseId),
                any(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                any(),
                any(),
                any(CodeChangeAdvisoryRequest.class)
        )).thenReturn(advisoryResponse("BACKEND_METHOD", true));
    }

    @Test
    void returnsNeedsCandidatesWhenNoHintsAndNoSourceAnalysis() {
        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        new CodeChangeAdvisoryOrchestrationRequest(
                                "problem",
                                "expected",
                                "actual",
                                List.of("BACKEND_METHOD"),
                                List.of(),
                                true,
                                true
                        )
                );

        assertThat(response.orchestrationStatus())
                .isEqualTo("NEEDS_CANDIDATES");
        assertThat(response.blockers())
                .contains("CODE_CHANGE_ADVISORY_CANDIDATES_MISSING");
        verify(advisoryService, never()).advise(
                any(), any(), anyBoolean(), anyInt(), anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void processesExplicitBackendMethodCandidateSuccessfully() {
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", true));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        request(List.of("BACKEND_METHOD"), candidate(
                                "OrderService.java",
                                "JAVA",
                                "void complete() {}"
                        ))
                );

        assertThat(response.orchestrationStatus()).isEqualTo("COMPLETED");
        assertThat(response.processedCandidateCount()).isEqualTo(1);
        assertThat(response.advisoryResultCount()).isEqualTo(1);
        assertThat(response.results().get(0).advisoryId()).isNotNull();
        assertThat(response.results().get(0).modelProfile())
                .isEqualTo("CODE_ADVISORY");
        assertThat(response.results().get(0).effectiveModelName())
                .isEqualTo("openai/gpt-4o-mini");
        assertThat(response.results().get(0).budgetTrackingEnabled()).isTrue();
        assertThat(response.results().get(0).budgetPeriod()).isEqualTo("WEEKLY");
        assertThat(response.results().get(0).weeklyBudgetUsd()).isEqualTo(200.0);
        assertThat(response.results().get(0).totalTokenCount()).isEqualTo(30);
        verify(advisoryService).advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(true),
                eq(60),
                eq(12000),
                eq(null),
                eq(null),
                any(CodeChangeAdvisoryRequest.class)
        );
    }

    @Test
    void invalidModelNameIsRejectedBeforeCandidateProcessing() {
        doThrow(new IllegalArgumentException(
                "MODEL_NAME_MUST_USE_OPENAI_PREFIX"
        )).when(advisoryService)
                .validateModelRouting(null, "gpt-3.5-turbo");

        assertThatThrownBy(() -> service.orchestrate(
                caseId,
                true,
                3,
                60,
                12000,
                true,
                null,
                "gpt-3.5-turbo",
                request(List.of("BACKEND_METHOD"), candidate(
                        "OrderService.java",
                        "JAVA",
                        "void complete() {}"
                ))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
        verify(candidateExtractionService, never()).hydrateCandidateHints(
                any(), any(), anyInt(), anyInt()
        );
        verify(advisoryService, never()).advise(
                any(), any(), anyBoolean(), anyInt(), anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void propagatesModelProfileToInternalAdvisoryCalls() {
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", true));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        service.orchestrate(
                caseId,
                true,
                3,
                60,
                12000,
                true,
                "CODE_ADVISORY",
                null,
                request(List.of("BACKEND_METHOD"), candidate(
                        "OrderService.java",
                        "JAVA",
                        "void complete() {}"
                ))
        );

        verify(advisoryService).advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(true),
                eq(60),
                eq(12000),
                eq("CODE_ADVISORY"),
                eq(null),
                any(CodeChangeAdvisoryRequest.class)
        );
    }

    @Test
    void processesFrontendComponentCandidateSuccessfully() {
        when(advisoryService.latestResult(caseId, "FRONTEND_COMPONENT"))
                .thenReturn(result("FRONTEND_COMPONENT", true));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        request(List.of("FRONTEND_COMPONENT"), candidate(
                                "CustomerPanel.tsx",
                                "TYPESCRIPT",
                                "export function CustomerPanel() { return null; }"
                        ))
                );

        assertThat(response.orchestrationStatus()).isEqualTo("COMPLETED");
        assertThat(response.results().get(0).advisoryMode())
                .isEqualTo("FRONTEND_COMPONENT");
    }

    @Test
    void usesExtractedCandidateWhenHintsAreEmptyAndSourceAnalysisExists() {
        when(candidateExtractionService.extractCandidateHints(
                eq(caseId),
                eq(3),
                eq(12000)
        )).thenReturn(List.of(candidate(
                "OrderService.java",
                "JAVA",
                "void complete() {}"
        )));
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", true));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        new CodeChangeAdvisoryOrchestrationRequest(
                                "problem",
                                "expected",
                                "actual",
                                List.of("BACKEND_METHOD"),
                                List.of(),
                                false,
                                true
                        )
                );

        assertThat(response.orchestrationStatus()).isEqualTo("COMPLETED");
        assertThat(response.processedCandidateCount()).isEqualTo(1);
        assertThat(response.advisoryResultCount()).isEqualTo(1);
        verify(candidateExtractionService).extractCandidateHints(
                caseId,
                3,
                12000
        );
    }

    @Test
    void hydratesExplicitCandidateWithoutSnippetBeforeAdvisory() {
        CodeChangeAdvisoryCandidateHint hydrated =
                new CodeChangeAdvisoryCandidateHint(
                        "backend",
                        "bss-backend/src/main/java/com/acme/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        "JAVA",
                        "public void updateUser() { run(); }",
                        "",
                        "",
                        List.of(
                                "hydratedFromSource=true",
                                "sourceCandidateSource=BITBUCKET",
                                "repositoryLogicalName=backend",
                                "normalizedFilePath=src/main/java/com/acme/UserServiceImpl.java",
                                "snippetChars=33"
                        )
                );
        when(candidateExtractionService.hydrateCandidateHints(
                eq(caseId),
                any(),
                eq(3),
                eq(12000)
        )).thenReturn(new CodeChangeCandidateExtractionService
                .HydratedCandidateHints(
                List.of(hydrated),
                List.of(),
                "BITBUCKET",
                List.of(),
                List.of(),
                List.of()
        ));
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(new CodeChangeAdvisoryResultSummary(
                        UUID.randomUUID(),
                        "BACKEND_METHOD",
                        "bss-backend/src/main/java/com/acme/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
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
                                "UserServiceImpl.java",
                                "updateUser",
                                "CONDITIONAL_MAPPING",
                                "Update user mapping.",
                                "pseudo patch"
                        ),
                        List.of(),
                        List.of(),
                        List.of("Add a focused unit test."),
                        "",
                        Map.of(
                                "filePath",
                                "bss-backend/src/main/java/com/acme/UserServiceImpl.java",
                                "language",
                                "JAVA",
                                "hydratedFromSource",
                                true,
                                "sourceCandidateSource",
                                "BITBUCKET",
                                "repositoryLogicalName",
                                "backend",
                                "normalizedFilePath",
                                "src/main/java/com/acme/UserServiceImpl.java",
                                "snippetChars",
                                33
                        ),
                        Instant.parse("2026-06-23T06:00:00Z")
                ));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        new CodeChangeAdvisoryOrchestrationRequest(
                                "problem",
                                "expected",
                                "actual",
                                List.of("BACKEND_METHOD"),
                                List.of(new CodeChangeAdvisoryCandidateHint(
                                        "backend",
                                        "bss-backend/src/main/java/com/acme/UserServiceImpl.java",
                                        "UserServiceImpl",
                                        "updateUser",
                                        "JAVA",
                                        "",
                                        "",
                                        "",
                                        List.of()
                                )),
                                false,
                                false
                        )
                );

        var captor = forClass(CodeChangeAdvisoryRequest.class);
        verify(advisoryService).advise(
                eq(caseId),
                eq("BACKEND_METHOD"),
                eq(true),
                eq(60),
                eq(12000),
                eq(null),
                eq(null),
                captor.capture()
        );
        assertThat(captor.getValue().codeSnippet())
                .contains("public void updateUser");
        assertThat(captor.getValue().constraints())
                .contains("sourceCandidateSource=BITBUCKET",
                        "repositoryLogicalName=backend");
        assertThat(response.orchestrationStatus()).isEqualTo("COMPLETED");
        assertThat(response.results().get(0).hydratedFromSource()).isTrue();
        assertThat(response.results().get(0).normalizedFilePath())
                .isEqualTo("src/main/java/com/acme/UserServiceImpl.java");
    }

    @Test
    void sensitiveMarkerSkipsCandidateSafely() {
        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        request(List.of("BACKEND_METHOD"), candidate(
                                "OrderService.java",
                                "JAVA",
                                "String value = \"Authorization: Bearer abc\";"
                        ))
                );

        assertThat(response.skippedCandidateCount()).isEqualTo(1);
        assertThat(response.warnings())
                .anyMatch(value -> value.contains(
                        "CANDIDATE_SENSITIVE_MARKER_REJECTED"
                ));
        verify(advisoryService, never()).advise(
                any(), any(), anyBoolean(), anyInt(), anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void llmFallbackResultIsReturnedAndEvaluationIsIncluded() {
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", false));
        when(advisoryService.summary(caseId)).thenReturn(summary(1));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        12000,
                        true,
                        request(List.of("BACKEND_METHOD"), candidate(
                                "OrderService.java",
                                "JAVA",
                                "void complete() {}"
                        ))
                );

        assertThat(response.orchestrationStatus()).isEqualTo("FALLBACK");
        assertThat(response.results().get(0).llmUsed()).isFalse();
        assertThat(response.evaluationSummary()).isNotNull();
    }

    @Test
    void maxCandidatesIsRespected() {
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", true));

        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        2,
                        60,
                        12000,
                        true,
                        new CodeChangeAdvisoryOrchestrationRequest(
                                "problem",
                                "expected",
                                "actual",
                                List.of("BACKEND_METHOD"),
                                List.of(
                                        candidate("A.java", "JAVA", "void a() {}"),
                                        candidate("B.java", "JAVA", "void b() {}"),
                                        candidate("C.java", "JAVA", "void c() {}")
                                ),
                                false,
                                false
                        )
                );

        assertThat(response.processedCandidateCount()).isEqualTo(2);
        verify(advisoryService, atMost(2)).advise(
                any(), any(), anyBoolean(), anyInt(), anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void maxSnippetCharsIsRespected() {
        CodeChangeAdvisoryOrchestrationResponse response =
                service.orchestrate(
                        caseId,
                        true,
                        3,
                        60,
                        5,
                        true,
                        request(List.of("BACKEND_METHOD"), candidate(
                                "OrderService.java",
                                "JAVA",
                                "123456"
                        ))
                );

        assertThat(response.skippedCandidateCount()).isEqualTo(1);
        assertThat(response.warnings())
                .contains("CANDIDATE_CODE_SNIPPET_TOO_LARGE:OrderService.java");
    }

    @Test
    void doesNotWriteFiles(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, "class OrderService {}\n");
        when(advisoryService.latestResult(caseId, "BACKEND_METHOD"))
                .thenReturn(result("BACKEND_METHOD", true));

        service.orchestrate(
                caseId,
                false,
                3,
                60,
                12000,
                true,
                request(List.of("BACKEND_METHOD"), candidate(
                        file.toString(),
                        "JAVA",
                        "void complete() {}"
                ))
        );

        assertThat(Files.readString(file)).isEqualTo("class OrderService {}\n");
    }

    private CodeChangeAdvisoryOrchestrationRequest request(
            List<String> modes,
            CodeChangeAdvisoryCandidateHint candidate
    ) {
        return new CodeChangeAdvisoryOrchestrationRequest(
                "Order status mismatch",
                "Order is COMPLETE",
                "Order stays PENDING",
                modes,
                List.of(candidate),
                true,
                false
        );
    }

    private CodeChangeAdvisoryCandidateHint candidate(
            String filePath,
            String language,
            String snippet
    ) {
        return new CodeChangeAdvisoryCandidateHint(
                filePath,
                "OrderService",
                "complete",
                language,
                snippet,
                "",
                "Sanitized log summary only",
                List.of("advisory only")
        );
    }

    private CodeChangeAdvisoryResponse advisoryResponse(
            String mode,
            boolean llmUsed
    ) {
        return new CodeChangeAdvisoryResponse(
                caseId,
                mode,
                llmUsed,
                llmUsed ? "SUCCESS" : "TIMEOUT",
                "HYPOTHESIS",
                "MEDIUM",
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
                false,
                llmUsed ? "" : "TIMEOUT",
                Map.of(
                        "filePath",
                        "OrderService.java",
                        "language",
                        "JAVA",
                        "modelProfile",
                        "CODE_ADVISORY",
                        "effectiveModelName",
                        "openai/gpt-4o-mini",
                        "budgetTrackingEnabled",
                        true,
                        "budgetPeriod",
                        "WEEKLY",
                        "weeklyBudgetUsd",
                        200.0,
                        "totalTokenCount",
                        30
                ),
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private CodeChangeAdvisoryResultSummary result(
            String mode,
            boolean llmUsed
    ) {
        return new CodeChangeAdvisoryResultSummary(
                UUID.randomUUID(),
                mode,
                "OrderService.java",
                "OrderService",
                "complete",
                "JAVA",
                llmUsed,
                llmUsed ? "SUCCESS" : "TIMEOUT",
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
                llmUsed ? "" : "TIMEOUT",
                Map.of(
                        "filePath",
                        "OrderService.java",
                        "language",
                        "JAVA",
                        "modelProfile",
                        "CODE_ADVISORY",
                        "effectiveModelName",
                        "openai/gpt-4o-mini",
                        "budgetTrackingEnabled",
                        true,
                        "budgetPeriod",
                        "WEEKLY",
                        "weeklyBudgetUsd",
                        200.0,
                        "totalTokenCount",
                        30
                ),
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private CodeChangeAdvisoryEvaluationSummaryResponse summary(int count) {
        return new CodeChangeAdvisoryEvaluationSummaryResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                count,
                null,
                null,
                null,
                null,
                0.66,
                count,
                0,
                0,
                count == 0 ? "NEEDS_MORE_CONTEXT" : "ADVISORY_READY",
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}

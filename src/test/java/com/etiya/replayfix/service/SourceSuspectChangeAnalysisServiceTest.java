package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceDiscoveredControllerEndpoint;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceLastCommitDiagnostic;
import com.etiya.replayfix.model.SourceSuspectChange;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSuspectChangeAnalysisServiceTest {

    @TempDir
    Path temporaryDirectory;

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private SuspectSignalExtractionService signalExtractionService;
    private CompanySourceReasoningService companyReasoningService;
    private SourceSuspectChangeAnalysisService service;
    private ReplayFixProperties properties;
    private SourceFlowAnchorExtractionService anchorExtractionService;
    private FlowAwareSourceDiscoveryService discoveryService;
    private SourceCandidateGitHistoryService gitHistoryService;
    private SourceReasoningContextBuilder contextBuilder;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        signalExtractionService = mock(SuspectSignalExtractionService.class);
        companyReasoningService = mock(CompanySourceReasoningService.class);
        properties = new ReplayFixProperties();
        properties.setWorkspaceDir(temporaryDirectory.resolve("work").toString());
        objectMapper = new ObjectMapper().findAndRegisterModules();
        anchorExtractionService = new SourceFlowAnchorExtractionService();
        discoveryService = new FlowAwareSourceDiscoveryService();
        gitHistoryService = new SourceCandidateGitHistoryService(
                properties,
                new EvidenceSanitizer()
        );
        contextBuilder = new SourceReasoningContextBuilder(
                evidenceRepository,
                new EvidenceSanitizer(),
                objectMapper
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.REPOSITORY_RESOLUTION
        )).thenReturn(List.of(repositoryResolution()));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.ROVO_RCA
        )).thenReturn(List.of(rovoEvidence()));
        when(signalExtractionService.extract(caseId, false))
                .thenReturn(signalResponse());

        service = service(discoveryService, gitHistoryService, contextBuilder);
    }

    @Test
    void endpointStatusRemainsHypothesisAndCompanyUnavailableWarning()
            throws Exception {
        writeWorkspaceFile();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE)
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.branch()).isEqualTo("test2");
        assertThat(response.llmUsed()).isFalse();
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
    }

    @Test
    void objectMapperCanSerializeDeterministicServiceResponse()
            throws Exception {
        writeWorkspaceFile();

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );
        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"status\":\"HYPOTHESIS\"");
        assertThat(json).contains("\"analysisMode\":\"DETERMINISTIC_ONLY\"");
        assertThat(json).contains("\"partial\":");
        assertThat(json).contains("\"phaseTimingsMs\"");
        assertThat(json).contains("\"lastCompletedPhase\"");
    }

    @Test
    void useCompanyLlmFalseReturnsDeterministicCandidates()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(discoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(historyResult());
        service = service(discovery, gitHistory, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.candidateFlowChain()).isNotEmpty();
        assertThat(response.suspectChanges()).isNotEmpty();
        assertThat(response.companyLlmStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(response.analysisMode()).isEqualTo("DETERMINISTIC_ONLY");
    }

    @Test
    void useCompanyLlmTrueAndSuccessReturnsLlmUsed()
            throws Exception {
        writeWorkspaceFile();
        useMockDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                true,
                List.of(llmSuspect()),
                "HYPOTHESIS",
                0.7,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of()
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8
        );

        assertThat(response.llmUsed()).isTrue();
        assertThat(response.analysisMode()).isEqualTo("COMPANY_LLM");
        assertThat(response.companyLlmStatus()).isEqualTo("SUCCESS");
        assertThat(response.status()).isEqualTo("HYPOTHESIS");
    }

    @Test
    void useCompanyLlmTrueAndTimeoutKeepsDeterministicCandidates()
            throws Exception {
        writeWorkspaceFile();
        useMockDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        )).thenAnswer(invocation -> {
            Thread.sleep(1_500);
            return new CompanySourceReasoningService.ReasoningResult(
                    true,
                    List.of(llmSuspect()),
                    "HYPOTHESIS",
                    0.7,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    List.of()
            );
        });

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                1
        );

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.analysisMode()).isEqualTo("DETERMINISTIC_ONLY");
        assertThat(response.companyLlmStatus()).isEqualTo("TIMEOUT");
        assertThat(response.companyLlmElapsedMs()).isGreaterThan(0L);
        assertThat(response.candidateFlowChain()).isNotEmpty();
        assertThat(response.suspectChanges()).isNotEmpty();
        assertThat(response.lastCommitDiagnostics()).isNotEmpty();
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_TIMEOUT)
                .doesNotContain(SourceSuspectChangeAnalysisService
                        .SOURCE_CHANGE_ANALYSIS_FAILED);
    }

    @Test
    void useCompanyLlmTrueAndUnavailableKeepsDeterministicCandidates()
            throws Exception {
        writeWorkspaceFile();
        useMockDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE)
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8
        );

        assertThat(response.companyLlmStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
        assertThat(response.candidateFlowChain()).isNotEmpty();
    }

    @Test
    void invalidCompanyLlmResponseKeepsDeterministicCandidates()
            throws Exception {
        writeWorkspaceFile();
        useMockDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE)
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8
        );

        assertThat(response.companyLlmStatus()).isEqualTo("ERROR");
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE)
                .doesNotContain(SourceSuspectChangeAnalysisService
                        .SOURCE_CHANGE_ANALYSIS_FAILED);
        assertThat(response.candidateFlowChain()).isNotEmpty();
    }

    @Test
    void minimalInvalidCompanyLlmResponseKeepsDeterministicCandidates()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                eq("MINIMAL")
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(
                        CompanySourceReasoningService
                                .COMPANY_LLM_EMPTY_RESPONSE,
                        CompanySourceReasoningService
                                .COMPANY_LLM_INVALID_RESPONSE
                ),
                "EMPTY_RESPONSE",
                "",
                500
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.llmUsed()).isFalse();
        assertThat(response.analysisMode()).isEqualTo("DETERMINISTIC_ONLY");
        assertThat(response.companyLlmStatus()).isEqualTo("ERROR");
        assertThat(response.companyLlmContextMode()).isEqualTo("MINIMAL");
        assertThat(response.candidateFlowChain())
                .extracting("className")
                .contains("UserController", "UserServiceImpl");
        assertThat(response.suspectChanges()).isNotEmpty();
        assertThat(response.companyLlmParseErrorCategory())
                .isEqualTo("EMPTY_RESPONSE");
        assertThat(response.companyLlmEffectiveOutputTokenLimit())
                .isEqualTo(500);
        assertThat(response.warnings())
                .contains(
                        CompanySourceReasoningService
                                .COMPANY_LLM_EMPTY_RESPONSE,
                        CompanySourceReasoningService
                                .COMPANY_LLM_INVALID_RESPONSE
                )
                .doesNotContain(SourceSuspectChangeAnalysisService
                        .SOURCE_CHANGE_ANALYSIS_FAILED);
    }

    @Test
    void invalidMinimalLlmResponseExposesSafeParseDiagnostics()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        String rawPreview = "not json token=secret reasoning_content=private "
                + "x".repeat(700)
                + "\n at com.example.Secret.method(Secret.java:42)";
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                eq("MINIMAL")
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE),
                "NON_JSON_RESPONSE",
                rawPreview,
                1000,
                java.util.Map.of(
                        "finishReason", "stop",
                        "hasContent", true,
                        "contentLength", rawPreview.length(),
                        "extractionSource", "content",
                        "parseErrorCategory", "NON_JSON_RESPONSE"
                )
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                1000
        );

        assertThat(response.companyLlmStatus()).isEqualTo("ERROR");
        assertThat(response.companyLlmParseErrorCategory())
                .isEqualTo("NON_JSON_RESPONSE");
        assertThat(response.companyLlmOutputPreview()).isNotBlank();
        assertThat(response.companyLlmOutputPreview())
                .hasSizeLessThanOrEqualTo(500);
        assertThat(response.companyLlmOutputPreview())
                .doesNotContain("reasoning_content")
                .doesNotContain("secret")
                .doesNotContain("Secret.java");
        assertThat(response.companyLlmEffectiveOutputTokenLimit())
                .isEqualTo(1000);
        assertThat(response.companyLlmResponseShape())
                .containsEntry("hasContent", true)
                .containsEntry("extractionSource", "content")
                .containsEntry("parseErrorCategory", "NON_JSON_RESPONSE");
        assertThat(response.candidateFlowChain())
                .extracting("className")
                .contains("UserController", "UserServiceImpl");
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE)
                .doesNotContain(SourceSuspectChangeAnalysisService
                        .SOURCE_CHANGE_ANALYSIS_FAILED);
    }

    @Test
    void compactPacketIncludesControllerServiceAndDtoCandidates()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true
        );

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(eq(caseId), packet.capture(), anyInt(), anyString());
        assertThat(packet.getValue()).contains("UserController");
        assertThat(packet.getValue()).contains("UserServiceImpl");
        assertThat(packet.getValue()).contains("UpdateAplUserPrefPrvncRequest");
        assertThat(response.companyLlmContextMode()).isEqualTo("COMPACT");
        assertThat(response.companyLlmPromptChars()).isGreaterThan(0);
    }

    @Test
    void compactPacketExcludesRawReasoningContent()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.ROVO_RCA
        )).thenReturn(List.of(evidence(
                EvidenceType.ROVO_RCA,
                "{\"summary\":\"flow\",\"reasoning_content\":\"SECRET_REASONING\"}"
        )));
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        service.analyze(caseId, 45, 20, 10, false, true);

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(eq(caseId), packet.capture(), anyInt(), anyString());
        assertThat(packet.getValue()).doesNotContain("SECRET_REASONING");
    }

    @Test
    void compactPacketExcludesAllDiscoveredEndpoints()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        service.analyze(caseId, 45, 20, 10, false, true);

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(eq(caseId), packet.capture(), anyInt(), anyString());
        assertThat(packet.getValue()).doesNotContain("OtherController");
        assertThat(packet.getValue()).doesNotContain("/other/debug");
    }

    @Test
    void compactPacketRespectsMaxChars()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "COMPACT",
                700,
                500
        );

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(eq(caseId), packet.capture(), anyInt(), anyString());
        assertThat(packet.getValue().length()).isLessThanOrEqualTo(700);
        assertThat(response.companyLlmPromptChars()).isLessThanOrEqualTo(700);
        assertThat(response.companyLlmMaxPromptChars()).isEqualTo(700);
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .COMPANY_LLM_CONTEXT_TRUNCATED);
    }

    @Test
    void minimalPacketIsSmallerThanCompactPacket()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        service.analyze(caseId, 45, 20, 10, false, true);
        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(
                eq(caseId),
                packet.capture(),
                anyInt(),
                anyString()
        );
        String compactPacket = packet.getValue();

        org.mockito.Mockito.clearInvocations(companyReasoningService);
        service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );
        verify(companyReasoningService).reason(
                eq(caseId),
                packet.capture(),
                anyInt(),
                eq("MINIMAL")
        );
        String minimalPacket = packet.getValue();

        assertThat(minimalPacket).contains("\"contextMode\":\"MINIMAL\"");
        assertThat(minimalPacket.length()).isLessThan(compactPacket.length());
    }

    @Test
    void minimalPacketIncludesControllerAndServiceCandidates()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(
                eq(caseId),
                packet.capture(),
                anyInt(),
                eq("MINIMAL")
        );
        assertThat(packet.getValue()).contains("UserController");
        assertThat(packet.getValue()).contains("UserServiceImpl");
        assertThat(packet.getValue()).doesNotContain(
                "UpdateAplUserPrefPrvncRequest.java"
        );
        assertThat(response.companyLlmContextMode()).isEqualTo("MINIMAL");
    }

    @Test
    void minimalPacketExcludesDiscoveredControllerEndpoints()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(
                eq(caseId),
                packet.capture(),
                anyInt(),
                eq("MINIMAL")
        );
        assertThat(packet.getValue()).doesNotContain("OtherController");
        assertThat(packet.getValue()).doesNotContain("/other/debug");
    }

    @Test
    void minimalPacketCapsSnippets()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );

        ArgumentCaptor<String> packet = ArgumentCaptor.forClass(String.class);
        verify(companyReasoningService).reason(
                eq(caseId),
                packet.capture(),
                anyInt(),
                eq("MINIMAL")
        );
        var json = objectMapper.readTree(packet.getValue());
        int totalSnippetChars = 0;
        for (var method : json.path("candidateMethods")) {
            totalSnippetChars += method.path("snippet").asText("").length();
        }

        assertThat(totalSnippetChars).isLessThanOrEqualTo(800);
    }

    @Test
    void sourceChangeAnalysisPassesOutputTokenLimit()
            throws Exception {
        writeWorkspaceFile();
        useExpandedDeterministicPipeline();
        when(companyReasoningService.reason(
                eq(caseId),
                anyString(),
                anyInt(),
                anyString()
        ))
                .thenReturn(successfulReasoning());

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        );

        ArgumentCaptor<Integer> maxOutputTokens =
                ArgumentCaptor.forClass(Integer.class);
        verify(companyReasoningService).reason(
                eq(caseId),
                anyString(),
                maxOutputTokens.capture(),
                eq("MINIMAL")
        );
        assertThat(maxOutputTokens.getValue()).isEqualTo(500);
        assertThat(response.companyLlmOutputTokenLimit()).isEqualTo(500);
        assertThat(response.companyLlmPromptHash()).isNotBlank();
    }

    @Test
    void defaultLlmContextModeIsCompact() throws Exception {
        writeWorkspaceFile();
        useMockDeterministicPipeline();

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.companyLlmContextMode()).isEqualTo("COMPACT");
        assertThat(response.companyLlmMaxPromptChars()).isEqualTo(12_000);
    }

    @Test
    void serviceReturnsWarningWhenSourceDiscoveryThrows() throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService throwingDiscovery =
                mock(FlowAwareSourceDiscoveryService.class);
        when(throwingDiscovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        ))
                .thenThrow(new IllegalStateException("boom stack trace"));
        service = service(throwingDiscovery, gitHistoryService, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService.SOURCE_DISCOVERY_FAILED);
        assertThat(response.toString())
                .doesNotContain("IllegalStateException")
                .doesNotContain("boom stack trace");
    }

    @Test
    void timeoutInSourceDiscoveryStillReturnsFlowAnchors() throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService slowDiscovery =
                mock(FlowAwareSourceDiscoveryService.class);
        when(slowDiscovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenAnswer(invocation -> {
            Thread.sleep(1_500);
            return discoveryResult();
        });
        service = service(slowDiscovery, gitHistoryService, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false,
                2_000,
                256,
                false,
                1,
                8
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.flowAnchors()).isNotEmpty();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .SOURCE_DISCOVERY_TIMEOUT);
        assertThat(response.currentPhaseOnTimeout())
                .isEqualTo("sourceDiscovery");
    }

    @Test
    void endpointStatusRemainsHypothesisWhenGitHistoryThrows()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService throwingGit =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        ))
                .thenReturn(discoveryResult());
        when(throwingGit.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenThrow(new IllegalStateException("git failed"));
        service = service(discovery, throwingGit, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService.SOURCE_GIT_HISTORY_FAILED);
    }

    @Test
    void timeoutInGitHistoryStillReturnsCandidateFlowChain()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService slowGit =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(discoveryResult());
        when(slowGit.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenAnswer(invocation -> {
            Thread.sleep(1_500);
            return new SourceCandidateGitHistoryService.HistoryResult(
                    List.of(),
                    List.of(),
                    List.of()
            );
        });
        service = service(discovery, slowGit, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false,
                2_000,
                256,
                false,
                10,
                1
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.candidateFlowChain()).isNotEmpty();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .SOURCE_GIT_HISTORY_TIMEOUT);
        assertThat(response.currentPhaseOnTimeout()).isEqualTo("gitHistory");
    }

    @Test
    void noRecentCommitsWarningRemainsWithLastCommitDiagnostics()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(discoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(new SourceCandidateGitHistoryService.HistoryResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SourceLastCommitDiagnostic(
                        "src/main/java/com/example/BusinessFlowController.java",
                        "abcdef123456",
                        "abcdef1",
                        "Test User",
                        "2020-01-01T00:00:00Z",
                        "old controller change"
                ))
        ));
        service = service(discovery, gitHistory, contextBuilder);

        var response = service.analyze(
                caseId,
                1,
                20,
                10,
                false,
                false
        );

        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .NO_RECENT_COMMITS_FOUND);
        assertThat(response.lastCommitDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("old controller change"));
    }

    @Test
    void endpointStatusRemainsHypothesisWhenContextBuilderThrows()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        SourceReasoningContextBuilder throwingContextBuilder =
                mock(SourceReasoningContextBuilder.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        ))
                .thenReturn(discoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(new SourceCandidateGitHistoryService.HistoryResult(
                List.of(),
                List.of(),
                List.of()
        ));
        when(throwingContextBuilder.build(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("context failed"));
        service = service(discovery, gitHistory, throwingContextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.sourceReasoningContext()).isNotNull();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .SOURCE_REASONING_CONTEXT_FAILED);
        assertThat(response.phaseTimingsMs()).containsKey("contextBuild");
        assertThat(response.lastCompletedPhase()).isNotBlank();
    }

    private void writeWorkspaceFile() throws Exception {
        Path file = Path.of(
                properties.getWorkspaceDir(),
                caseId.toString(),
                "repositories",
                "backend",
                "src/main/java/com/example/BusinessFlowController.java"
        );
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;
                public class BusinessFlowController {
                    @PostMapping("/businessFlow/initialize")
                    public void initialize() {}
                }
                """);
    }

    private FlowAwareSourceDiscoveryService.DiscoveryResult discoveryResult() {
        return new FlowAwareSourceDiscoveryService.DiscoveryResult(
                List.of(new SourceCandidateFlowChainItem(
                        "CONTROLLER",
                        "src/main/java/com/example/BusinessFlowController.java",
                        "BusinessFlowController",
                        "initialize",
                        List.of("/businessFlow/initialize"),
                        "Controller mapping annotation matched endpoint anchor.",
                        "HYPOTHESIS"
                )),
                List.of("src/main/java/com/example/BusinessFlowController.java"),
                List.of(),
                java.util.Map.of()
        );
    }

    private SourceCandidateGitHistoryService.HistoryResult historyResult() {
        return new SourceCandidateGitHistoryService.HistoryResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SourceLastCommitDiagnostic(
                        "src/main/java/com/example/BusinessFlowController.java",
                        "abcdef123456",
                        "abcdef1",
                        "Test User",
                        "2020-01-01T00:00:00Z",
                        "old controller change"
                ))
        );
    }

    private void useMockDeterministicPipeline() {
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(discoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(historyResult());
        service = service(discovery, gitHistory, contextBuilder);
    }

    private void useExpandedDeterministicPipeline() {
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(expandedDiscoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(historyResult());
        service = service(discovery, gitHistory, contextBuilder);
    }

    private FlowAwareSourceDiscoveryService.DiscoveryResult
    expandedDiscoveryResult() {
        List<SourceCandidateFlowChainItem> chain = List.of(
                new SourceCandidateFlowChainItem(
                        "CONTROLLER",
                        "ControllerBackend/src/main/java/UserController.java",
                        "UserController",
                        "updateUserParty",
                        List.of("/user/region/update"),
                        "Controller endpoint mapping matched.",
                        "HYPOTHESIS"
                ),
                new SourceCandidateFlowChainItem(
                        "SERVICE_IMPL",
                        "CrmBackend/src/main/java/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        List.of("userService.updateUser(request)"),
                        "Controller direct service call resolved.",
                        "HYPOTHESIS"
                ),
                new SourceCandidateFlowChainItem(
                        "DTO",
                        "CrmBackend/src/main/java/UpdateAplUserPrefPrvncRequest.java",
                        "UpdateAplUserPrefPrvncRequest",
                        "",
                        List.of("request DTO"),
                        "Request DTO referenced by controller/service chain.",
                        "HYPOTHESIS"
                ),
                new SourceCandidateFlowChainItem(
                        "REPOSITORY",
                        "CrmBackend/src/main/java/UserRepository.java",
                        "UserRepository",
                        "save",
                        List.of("fallback"),
                        "Lower priority fallback.",
                        "HYPOTHESIS"
                )
        );
        List<SourceCandidateMethod> methods = List.of(
                new SourceCandidateMethod(
                        "ControllerBackend/src/main/java/UserController.java",
                        "UserController",
                        "updateUserParty",
                        10,
                        20,
                        List.of("/user/region/update"),
                        "public void updateUserParty(UpdateAplUserPrefPrvncRequest request) { userService.updateUser(request); }"
                                + " preferredProvince".repeat(80)
                ),
                new SourceCandidateMethod(
                        "CrmBackend/src/main/java/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        30,
                        50,
                        List.of("userService.updateUser(request)"),
                        "public void updateUser(UpdateAplUserPrefPrvncRequest request) { validate(request); }"
                                + " regionValidation".repeat(80)
                ),
                new SourceCandidateMethod(
                        "CrmBackend/src/main/java/UpdateAplUserPrefPrvncRequest.java",
                        "UpdateAplUserPrefPrvncRequest",
                        "",
                        1,
                        15,
                        List.of("request DTO"),
                        "public class UpdateAplUserPrefPrvncRequest { private String preferredProvince; }"
                )
        );
        return new FlowAwareSourceDiscoveryService.DiscoveryResult(
                chain,
                chain.stream().map(SourceCandidateFlowChainItem::file).toList(),
                methods,
                java.util.Map.of(),
                5,
                3,
                2,
                List.of("/user/region/update"),
                List.of(),
                List.of(new SourceDiscoveredControllerEndpoint(
                        "ControllerBackend/src/main/java/OtherController.java",
                        "OtherController",
                        "debug",
                        "GET",
                        "/other",
                        "/debug",
                        "/other/debug"
                )),
                1,
                List.of("UserService"),
                List.of("CrmBackend/src/main/java/UserServiceImpl.java"),
                List.of()
        );
    }

    private CompanySourceReasoningService.ReasoningResult successfulReasoning() {
        return new CompanySourceReasoningService.ReasoningResult(
                true,
                List.of(llmSuspect()),
                "HYPOTHESIS",
                0.7,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of()
        );
    }

    private SourceSuspectChange llmSuspect() {
        return new SourceSuspectChange(
                "src/main/java/com/example/BusinessFlowController.java",
                "BusinessFlowController",
                "initialize",
                "CONTROLLER",
                "/businessFlow/initialize",
                List.of("/businessFlow/initialize"),
                0,
                List.of(),
                "LLM hypothesis",
                0.7,
                "HYPOTHESIS",
                List.of()
        );
    }

    private SourceSuspectChangeAnalysisService service(
            FlowAwareSourceDiscoveryService discovery,
            SourceCandidateGitHistoryService gitHistory,
            SourceReasoningContextBuilder builder
    ) {
        return new SourceSuspectChangeAnalysisService(
                caseRepository,
                evidenceRepository,
                signalExtractionService,
                anchorExtractionService,
                discovery,
                gitHistory,
                builder,
                companyReasoningService,
                objectMapper,
                properties
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        replayCase.setSourceCommit("abc123");
        return replayCase;
    }

    private SuspectSignalExtractionResponse signalResponse() {
        return new SuspectSignalExtractionResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "",
                List.of(new SuspectSourceSignal(
                        "/businessFlow/initialize",
                        SuspectSignalCategory.ENDPOINT,
                        SuspectSignalStrength.STRONG,
                        List.of("ROVO_RCA"),
                        "test"
                )),
                0,
                List.of()
        );
    }

    private EvidenceEntity repositoryResolution() {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.REPOSITORY_RESOLUTION);
        evidence.setSource("test");
        evidence.setContentText("""
                {
                  "projectKey": "DCE",
                  "primaryRepositorySlug": "backend",
                  "sourceBranch": "test2"
                }
                """);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }

    private EvidenceEntity rovoEvidence() {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.ROVO_RCA);
        evidence.setSource("test");
        evidence.setContentText("{\"summary\":\"flow\"}");
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }

    private EvidenceEntity evidence(EvidenceType type, String content) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(type);
        evidence.setSource("test");
        evidence.setContentText(content);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

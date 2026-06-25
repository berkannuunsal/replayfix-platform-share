package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.*;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import com.etiya.replaylab.model.StructuredAiRootCauseAnalysis;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.event.AiAnalysisCompletedEvent;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiIncidentAnalysisServiceTest {

    @Mock
    private ReplayLabProperties properties;
    @Mock
    private AiProviderClientFactory providerFactory;
    @Mock
    private ReplayCaseRepository caseRepository;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AiProviderClient mockProvider;
    
    private AiIncidentAnalysisService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        
        ReplayLabProperties.Ai aiConfig = new ReplayLabProperties.Ai();
        aiConfig.setEnabled(true);
        aiConfig.setProvider(AiProviderType.MOCK);
        aiConfig.setModel("mock-replaylab-v1");
        aiConfig.setTemperature(0.1);
        aiConfig.setMaxInputChars(120000);
        aiConfig.setMaxOutputChars(30000);
        
        when(properties.getAi()).thenReturn(aiConfig);
        
        service = new AiIncidentAnalysisService(
                properties,
                providerFactory,
                caseRepository,
                evidenceRepository,
                auditService,
                eventPublisher,
                objectMapper
        );
    }

    @Test
    void testAnalyze_WhenAiDisabled_ThrowsException() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        properties.getAi().setEnabled(false);
        
        assertThatThrownBy(() -> service.analyze(caseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        
        verify(auditService).record(eq(caseId), eq("AI_ANALYSIS_REQUESTED"), any(), any());
    }

    @Test
    void testAnalyze_WhenCaseNotFound_ThrowsException() {
        UUID caseId = UUID.randomUUID();
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.analyze(caseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void testAnalyze_WhenInsufficientEvidence_NoJira_ThrowsException() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of(
                createEvidence(EvidenceType.LOKI_LOGS)
        ));
        
        assertThatThrownBy(() -> service.analyze(caseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSUFFICIENT_EVIDENCE")
                .hasMessageContaining("Jira");
    }

    @Test
    void testAnalyze_WhenInsufficientEvidence_NoRuntime_ThrowsException() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of(
                createEvidence(EvidenceType.JIRA_ISSUE)
        ));
        
        assertThatThrownBy(() -> service.analyze(caseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSUFFICIENT_EVIDENCE")
                .hasMessageContaining("runtime");
    }

    @Test
    void testAnalyze_Success_WithConfidenceCap() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS),
                createEvidence(EvidenceType.TEMPO_TRACE)
        );
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("MOCK");
        
        StructuredAiRootCauseAnalysis mockAnalysis = createMockAnalysis(caseId, 0.95); // High confidence
        AiGenerationResponse mockResponse = createSuccessResponse(mockAnalysis);
        when(mockProvider.generate(any(AiGenerationRequest.class))).thenReturn(mockResponse);
        
        StructuredAiRootCauseAnalysis result = service.analyze(caseId);
        
        assertThat(result).isNotNull();
        assertThat(result.confidence()).isLessThanOrEqualTo(0.75); // MOCK cap
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.synthetic()).isTrue();
        
        ArgumentCaptor<EvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(EvidenceEntity.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());
        
        EvidenceEntity savedEvidence = evidenceCaptor.getValue();
        assertThat(savedEvidence.getEvidenceType()).isEqualTo(EvidenceType.AI_ROOT_CAUSE);
        assertThat(savedEvidence.getSource()).contains("ai-provider-mock");
        assertThat(savedEvidence.getCaseId()).isEqualTo(caseId);
        
        verify(auditService).record(eq(caseId), eq("AI_ANALYSIS_COMPLETED"), any(), any());
        verify(eventPublisher).publishEvent(any(AiAnalysisCompletedEvent.class));
    }

    @Test
    void testAnalyze_PreservesDeterministicRca() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        EvidenceEntity deterministicRca = createEvidence(EvidenceType.DETERMINISTIC_ROOT_CAUSE);
        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS),
                deterministicRca
        );
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("MOCK");
        
        StructuredAiRootCauseAnalysis mockAnalysis = createMockAnalysis(caseId, 0.65);
        AiGenerationResponse mockResponse = createSuccessResponse(mockAnalysis);
        when(mockProvider.generate(any(AiGenerationRequest.class))).thenReturn(mockResponse);
        
        service.analyze(caseId);
        
        verify(evidenceRepository, never()).delete(deterministicRca);
        verify(evidenceRepository, times(1)).save(any(EvidenceEntity.class));
    }

    @Test
    void testAnalyze_EventPublishedAfterSuccessfulAnalysis() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS)
        );
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("MOCK");
        
        StructuredAiRootCauseAnalysis mockAnalysis = createMockAnalysis(caseId, 0.65);
        AiGenerationResponse mockResponse = createSuccessResponse(mockAnalysis);
        when(mockProvider.generate(any(AiGenerationRequest.class))).thenReturn(mockResponse);
        
        // Analysis should succeed and publish event
        StructuredAiRootCauseAnalysis result = service.analyze(caseId);
        
        assertThat(result).isNotNull();
        assertThat(result.caseId()).isEqualTo(caseId);
        
        // Evidence should be persisted
        verify(evidenceRepository).save(argThat(evidence -> 
                evidence.getEvidenceType() == EvidenceType.AI_ROOT_CAUSE &&
                evidence.getCaseId().equals(caseId)
        ));
        
        // Audit should be recorded
        verify(auditService).record(eq(caseId), eq("AI_ANALYSIS_COMPLETED"), any(), any());
        
        // Event should be published for AFTER_COMMIT notification
        ArgumentCaptor<AiAnalysisCompletedEvent> eventCaptor = ArgumentCaptor.forClass(AiAnalysisCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        
        AiAnalysisCompletedEvent event = eventCaptor.getValue();
        assertThat(event.caseId()).isEqualTo(caseId);
        assertThat(event.notificationType()).isEqualTo(NotificationType.AI_ANALYSIS_COMPLETED);
        assertThat(event.synthetic()).isTrue();
    }

    @Test
    void testAnalyze_SuccessfulMockAnalysisCommitsAiEvidence() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS),
                createEvidence(EvidenceType.TEMPO_TRACE)
        );
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("MOCK");
        
        StructuredAiRootCauseAnalysis mockAnalysis = createMockAnalysis(caseId, 0.72);
        AiGenerationResponse mockResponse = createSuccessResponse(mockAnalysis);
        when(mockProvider.generate(any(AiGenerationRequest.class))).thenReturn(mockResponse);
        
        // MOCK analysis should succeed and commit AI evidence
        StructuredAiRootCauseAnalysis result = service.analyze(caseId);
        
        assertThat(result).isNotNull();
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.synthetic()).isTrue();
        assertThat(result.provider()).isEqualTo("MOCK");
        
        // AI evidence should be persisted with correct type
        ArgumentCaptor<EvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(EvidenceEntity.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());
        
        EvidenceEntity savedEvidence = evidenceCaptor.getValue();
        assertThat(savedEvidence.getEvidenceType()).isEqualTo(EvidenceType.AI_ROOT_CAUSE);
        assertThat(savedEvidence.getCaseId()).isEqualTo(caseId);
        assertThat(savedEvidence.getSource()).isEqualTo("ai-provider-mock");
        assertThat(savedEvidence.getConfidence()).isEqualTo(0.72);
        assertThat(savedEvidence.isSanitized()).isTrue();
        
        // Audit should be recorded
        verify(auditService).record(eq(caseId), eq("AI_ANALYSIS_COMPLETED"), any(), any());
        
        // Event should be published
        verify(eventPublisher).publishEvent(any(AiAnalysisCompletedEvent.class));
    }

    @Test
    void testAnalyze_CompanyLlmPersistsCompanyEvidenceSource() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().setModel("company-model");

        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS),
                createEvidence(EvidenceType.AI_INPUT_BUNDLE)
        );

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("COMPANY_LLM");

        StructuredAiRootCauseAnalysis companyAnalysis =
                new StructuredAiRootCauseAnalysis(
                        caseId,
                        "COMPANY_LLM_ANALYSIS",
                        "summary",
                        "API validation mismatch",
                        "backend",
                        0.42,
                        List.of("fact"),
                        List.of(),
                        List.of("inference"),
                        List.of(),
                        List.of("review action"),
                        List.of("missing trace"),
                        "Human review required",
                        "COMPANY_LLM",
                        "company-model",
                        false,
                        List.of("warning")
                );
        when(mockProvider.generate(any(AiGenerationRequest.class)))
                .thenReturn(new AiGenerationResponse(
                        true,
                        "COMPANY_LLM",
                        "company-model",
                        UUID.randomUUID().toString(),
                        "completed",
                        100L,
                        1000,
                        2000,
                        objectMapper.valueToTree(companyAnalysis),
                        List.of(),
                        null,
                        null
                ));

        service.analyze(caseId);

        ArgumentCaptor<EvidenceEntity> evidenceCaptor =
                ArgumentCaptor.forClass(EvidenceEntity.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());

        EvidenceEntity savedEvidence = evidenceCaptor.getValue();
        assertThat(savedEvidence.getEvidenceType()).isEqualTo(EvidenceType.AI_ROOT_CAUSE);
        assertThat(savedEvidence.getSource()).isEqualTo("company-llm");
        JsonNode content = objectMapper.readTree(savedEvidence.getContentText());
        assertThat(content.path("provider").asText()).isEqualTo("COMPANY_LLM");
        assertThat(content.path("model").asText()).isEqualTo("company-model");
        assertThat(content.path("status").asText()).isEqualTo("HYPOTHESIS");
        assertThat(content.path("probableRootCause").asText())
                .isEqualTo("API validation mismatch");
        assertThat(content.path("facts").get(0).asText()).isEqualTo("fact");
        assertThat(content.path("inferences").get(0).asText()).isEqualTo("inference");
        assertThat(content.path("unknowns").get(0).asText()).isEqualTo("missing trace");
        assertThat(content.path("recommendedActions").get(0).asText())
                .isEqualTo("review action");
    }

    @Test
    void testAnalyze_ConfidenceCapByEvidenceType() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = createCaseEntity(caseId);
        
        // Only Jira + Loki -> cap should be 0.65
        List<EvidenceEntity> evidenceList = List.of(
                createEvidence(EvidenceType.JIRA_ISSUE),
                createEvidence(EvidenceType.LOKI_LOGS)
        );
        
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(evidenceList);
        when(providerFactory.getProvider()).thenReturn(mockProvider);
        when(mockProvider.providerName()).thenReturn("MOCK");
        
        StructuredAiRootCauseAnalysis mockAnalysis = createMockAnalysis(caseId, 0.85);
        AiGenerationResponse mockResponse = createSuccessResponse(mockAnalysis);
        when(mockProvider.generate(any(AiGenerationRequest.class))).thenReturn(mockResponse);
        
        StructuredAiRootCauseAnalysis result = service.analyze(caseId);
        
        assertThat(result.confidence()).isLessThanOrEqualTo(0.65);
        assertThat(result.warnings()).anyMatch(w -> w.contains("capped"));
    }

    private ReplayCaseEntity createCaseEntity(UUID caseId) {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("TEST-123");
        return entity;
    }

    private EvidenceEntity createEvidence(EvidenceType type) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setEvidenceType(type);
        evidence.setSource("test-source");
        evidence.setContentText("test content");
        evidence.setSanitized(true);
        return evidence;
    }

    private StructuredAiRootCauseAnalysis createMockAnalysis(UUID caseId, double confidence) {
        return new StructuredAiRootCauseAnalysis(
                caseId,
                "MOCK_AI_ANALYSIS",
                "Test executive summary",
                "Test root cause",
                "test-service",
                confidence,
                List.of("Test failure chain"),
                List.of(UUID.randomUUID().toString()),
                List.of("Test hypothesis"),
                List.of("Test regression"),
                List.of("Test fix direction"),
                List.of("Test missing"),
                "Test action",
                "MOCK",
                "mock-replaylab-v1",
                true,
                List.of("Synthetic AI response")
        );
    }

    private AiGenerationResponse createSuccessResponse(StructuredAiRootCauseAnalysis analysis) {
        return new AiGenerationResponse(
                true,
                "MOCK",
                "mock-replaylab-v1",
                UUID.randomUUID().toString(),
                "completed",
                100L,
                1000,
                2000,
                objectMapper.valueToTree(analysis),
                List.of("Test warning"),
                null,
                null
        );
    }
}

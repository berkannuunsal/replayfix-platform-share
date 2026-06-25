package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.*;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MockAiProviderClientTest {

    @Mock
    private ReplayLabProperties properties;

    @Mock
    private EvidenceRepository evidenceRepository;

    private MockAiProviderClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        
        ReplayLabProperties.Ai aiConfig = new ReplayLabProperties.Ai();
        aiConfig.setEnabled(true);
        aiConfig.setProvider(AiProviderType.MOCK);
        aiConfig.setModel("mock-replaylab-v1");
        
        when(properties.getAi()).thenReturn(aiConfig);
        
        client = new MockAiProviderClient(properties, evidenceRepository, objectMapper);
    }

    @Test
    void testConnectivity_ReturnsSuccess() {
        AiConnectivityResult result = client.connectivity();
        
        assertThat(result.success()).isTrue();
        assertThat(result.enabled()).isTrue();
        assertThat(result.provider()).isEqualTo("MOCK");
        assertThat(result.model()).isEqualTo("mock-replaylab-v1");
        assertThat(result.warnings()).contains("Synthetic AI provider for local validation.");
    }

    @Test
    void testGenerate_ReturnsSyntheticResponse() {
        UUID caseId = UUID.randomUUID();
        
        EvidenceEntity jira = new EvidenceEntity();
        jira.setId(UUID.randomUUID());
        jira.setEvidenceType(EvidenceType.JIRA_ISSUE);
        
        EvidenceEntity loki = new EvidenceEntity();
        loki.setId(UUID.randomUUID());
        loki.setEvidenceType(EvidenceType.LOKI_LOGS);
        
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of(jira, loki));
        
        AiGenerationRequest request = new AiGenerationRequest(
                caseId,
                "FULL_INCIDENT_ANALYSIS",
                "system prompt",
                "user prompt",
                "mock-replaylab-v1",
                0.1,
                30000,
                true,
                Map.of()
        );
        
        AiGenerationResponse response = client.generate(request);
        
        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("MOCK");
        assertThat(response.structuredResponse()).isNotNull();
        
        StructuredAiRootCauseAnalysis analysis = objectMapper.convertValue(
                response.structuredResponse(),
                StructuredAiRootCauseAnalysis.class
        );
        
        assertThat(analysis.synthetic()).isTrue();
        assertThat(analysis.analysisType()).isEqualTo("MOCK_AI_ANALYSIS");
        assertThat(analysis.confidence()).isLessThanOrEqualTo(0.75);
        assertThat(analysis.supportingEvidenceIds()).hasSize(2);
        assertThat(analysis.warnings()).contains("Synthetic AI response for local validation.");
    }

    @Test
    void testGenerate_ConfidenceCapped() {
        UUID caseId = UUID.randomUUID();
        
        EvidenceEntity jira = new EvidenceEntity();
        jira.setId(UUID.randomUUID());
        jira.setEvidenceType(EvidenceType.JIRA_ISSUE);
        
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of(jira));
        
        AiGenerationRequest request = new AiGenerationRequest(
                caseId, "FULL_INCIDENT_ANALYSIS", "", "", "mock-replaylab-v1", 0.1, 30000, true, Map.of()
        );
        
        AiGenerationResponse response = client.generate(request);
        
        StructuredAiRootCauseAnalysis analysis = objectMapper.convertValue(
                response.structuredResponse(),
                StructuredAiRootCauseAnalysis.class
        );
        
        assertThat(analysis.confidence()).isLessThanOrEqualTo(0.45);
    }

    @Test
    void testSupportsStructuredOutput() {
        assertThat(client.supportsStructuredOutput()).isTrue();
    }

    @Test
    void testProviderName() {
        assertThat(client.providerName()).isEqualTo("MOCK");
    }
}

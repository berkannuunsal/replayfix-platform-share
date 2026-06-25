package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.IntegrationModels;
import com.etiya.replayfix.model.RovoRcaImportResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RovoRcaNormalizationTest {

    private RovoRcaImporterService service;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private JiraClient jiraClient;
    private EvidenceService evidenceService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        jiraClient = mock(JiraClient.class);
        evidenceService = mock(EvidenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        service = new RovoRcaImporterService(
                caseRepository,
                evidenceRepository,
                jiraClient,
                evidenceService,
                objectMapper
        );
    }

    @Test
    void shouldNormalizeRelatedJiraIssuesFromStringArray() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE",
                  "relatedJiraIssues": ["FIZZMS-7819", "FIZZMS-7820"]
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertTrue(response.normalized());
        assertFalse(response.normalizationWarnings().isEmpty());
        assertTrue(response.normalizationWarnings().stream()
                .anyMatch(w -> w.contains("relatedJiraIssues")));
    }

    @Test
    void shouldNormalizeRelatedJiraIssuesFromObjectArray() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE",
                  "relatedJiraIssues": [
                    {"jiraKey": "FIZZMS-7819", "reason": "Related outage"}
                  ]
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        // No normalization needed for proper object format
    }

    @Test
    void shouldNormalizeEvidenceMatrixReferencesFromString() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE",
                  "evidenceMatrix": {
                    "loki": {
                      "references": "ReplayLab Snapshot"
                    }
                  }
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertTrue(response.normalized());
        assertTrue(response.normalizationWarnings().stream()
                .anyMatch(w -> w.contains("evidenceMatrix.loki.references")));
    }

    @Test
    void shouldNormalizeFailureChainToProbableFailureChain() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE",
                  "failureChain": [
                    "FACT: Service returned 500",
                    "INFERENCE: Likely database timeout"
                  ]
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertTrue(response.normalized());
        assertTrue(response.normalizationWarnings().stream()
                .anyMatch(w -> w.contains("failureChain")));
    }

    @Test
    void shouldDefaultMissingStatus() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertTrue(response.normalized());
        assertTrue(response.normalizationWarnings().stream()
                .anyMatch(w -> w.contains("status")));
    }

    @Test
    void shouldClampConfidenceOutOfRange() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 1.5,
                  "probableRootCause": "NPE"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertTrue(response.normalized());
        assertEquals(1.0, response.rovoConfidence());
        assertTrue(response.normalizationWarnings().stream()
                .anyMatch(w -> w.contains("Clamped confidence")));
    }
}

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

class RovoRcaImporterServiceTest {

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
    void shouldImportPlainTextRovoRcaComment() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String plainTextComment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.85,
                  "probableRootCause": "NPE in service"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), plainTextComment)
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
        assertEquals("IMPORTED", response.importStatus());
        assertNotNull(response.diagnostics());
        assertEquals(1, response.diagnostics().commentsScanned());
        assertEquals(1, response.diagnostics().pagesScanned());
        assertNotNull(response.diagnostics().detectedBodyFormats());
        assertTrue(response.diagnostics().detectedBodyFormats().contains("PLAIN_TEXT"));
        assertNotNull(response.normalizationWarnings());
    }

    @Test
    void shouldImportAdfParagraphRovoRcaComment() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        // Simulated ADF comment body already extracted to plain text by JiraHttpClient
        String adfExtractedText = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.90,
                  "probableRootCause": "Database timeout"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10002", "Rovo Bot", Instant.now(), adfExtractedText)
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
        assertEquals(0.90, response.rovoConfidence());
        assertNotNull(response.diagnostics());
        assertEquals(1, response.diagnostics().commentsScanned());
        assertNotNull(response.diagnostics().candidateCommentIds());
        assertNotNull(response.normalizationWarnings());
    }

    @Test
    void shouldImportAdfCodeBlockRovoRcaComment() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String codeBlockExtractedText = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.95,
                  "probableRootCause": "Memory leak in cache"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10003", "Rovo Bot", Instant.now(), codeBlockExtractedText)
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
        assertEquals("Memory leak in cache", response.probableRootCause());
        assertNotNull(response.diagnostics());
        assertNotNull(response.diagnostics().importedCommentId());
        assertNotNull(response.normalizationWarnings());
    }

    @Test
    void shouldHandleMarkerSplitAcrossMultipleTextNodes() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        // Even if split across nodes, JiraHttpClient concatenates all text
        String concatenatedText = """
                REPLAYFIX_ROVO_RCA_V1
                {"schemaVersion":"1.0","jiraKey":"TEST-123","confidence":0.88,"probableRootCause":"Config error"}
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10004", "Rovo Bot", Instant.now(), concatenatedText)
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
        assertEquals(0.88, response.rovoConfidence());
        assertNotNull(response.diagnostics());
        assertNotNull(response.normalizationWarnings());
    }

    @Test
    void shouldRejectInvalidJsonBetweenMarkers() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String invalidJsonComment = """
                REPLAYFIX_ROVO_RCA_V1
                {invalid json here
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10005", "Rovo Bot", Instant.now(), invalidJsonComment)
        );

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertFalse(response.imported());
        assertEquals("INVALID_JSON", response.importStatus());
        assertNotNull(response.error());
        assertTrue(response.error().contains("INVALID_ROVO_RCA_JSON"));
    }

    @Test
    void shouldPreventDuplicateImport() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String rovoRcaComment = """
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
                new IntegrationModels.JiraComment("10006", "Rovo Bot", Instant.now(), rovoRcaComment)
        );

        EvidenceEntity existingEvidence = new EvidenceEntity();
        UUID existingId = UUID.randomUUID();
        existingEvidence.setId(existingId);
        existingEvidence.setContentText("{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"confidence\":0.85,\"probableRootCause\":\"NPE\"}");

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(existingEvidence));

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertFalse(response.imported());
        assertEquals("DUPLICATE", response.importStatus());
        assertEquals(existingId, response.existingEvidenceId());
        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldReplaceLegacyJsonOnlyEvidenceWhenForceImporting() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String rovoRcaComment = """
                Human report
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
                new IntegrationModels.JiraComment("10006", "Rovo Bot", Instant.now(), rovoRcaComment)
        );

        EvidenceEntity existingEvidence = new EvidenceEntity();
        UUID existingId = UUID.randomUUID();
        existingEvidence.setId(existingId);
        existingEvidence.setContentText("{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"confidence\":0.85,\"probableRootCause\":\"NPE\"}");

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(existingEvidence));
        when(evidenceService.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evidenceRepository.save(existingEvidence)).thenReturn(existingEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId, true);

        // Then
        assertTrue(response.imported());
        assertEquals(existingId, response.evidenceId());
        assertEquals("IMPORTED", response.importStatus());
        assertEquals("HYPOTHESIS", response.rcaStatus());
        assertTrue(existingEvidence.getContentText().contains("\"rawHumanReport\":\"Human report\""));
        assertTrue(existingEvidence.getContentText().contains("\"normalizedRovoJson\""));
        verify(evidenceRepository).save(existingEvidence);
        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldPersistSuccessfulRovoRcaEvidence() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String rovoRcaComment = """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "confidence": 0.92,
                  "probableRootCause": "Connection pool exhausted"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10007", "Rovo Bot", Instant.now(), rovoRcaComment)
        );

        UUID evidenceId = UUID.randomUUID();
        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(evidenceId);

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), eq("rovo-incident-commander"), any(), eq(true)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        assertEquals(evidenceId, response.evidenceId());
        assertNotNull(response.normalizationWarnings());
        verify(evidenceService).save(
                eq(caseId),
                eq(EvidenceType.ROVO_RCA),
                eq("rovo-incident-commander"),
                any(),
                eq(true)
        );
    }

    @Test
    void shouldIncludeDiagnosticsInResponse() {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment1 = "Regular comment";
        String comment2 = "REPLAYFIX_ROVO_RCA_V1\n{\"schemaVersion\":\"1.0\",\"confidence\":0.9}\nREPLAYFIX_ROVO_RCA_END";
        String comment3 = "Another comment with REPLAYFIX_ROVO_RCA_V1 marker only";

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("1", "User1", Instant.parse("2024-01-01T10:00:00Z"), comment1),
                new IntegrationModels.JiraComment("2", "Rovo Bot", Instant.parse("2024-01-02T11:00:00Z"), comment2),
                new IntegrationModels.JiraComment("3", "User2", Instant.parse("2024-01-03T12:00:00Z"), comment3)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = service.importFromJira(caseId);

        // Then
        assertNotNull(response.diagnostics());
        assertEquals(3, response.diagnostics().commentsScanned());
        assertEquals(1, response.diagnostics().pagesScanned());
        assertEquals(2, response.diagnostics().markerStartFoundCount());
        assertEquals(1, response.diagnostics().markerEndFoundCount());
        assertEquals(1, response.diagnostics().candidateCommentIds().size());
        assertEquals("User2", response.diagnostics().latestCommentAuthor());
        assertNotNull(response.diagnostics().latestCommentCreatedAt());
        assertNotNull(response.diagnostics().detectedBodyFormats());
        assertNotNull(response.diagnostics().normalizedTextLengths());
        assertNotNull(response.diagnostics().importedCommentId());
        assertNotNull(response.diagnostics().importedBodyFormat());
    }
}

package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.*;
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

class RovoRcaEnvelopeTest {

    private RovoRcaImporterService importerService;
    private IncidentDashboardService dashboardService;
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
        objectMapper = new ObjectMapper();

        importerService = new RovoRcaImporterService(
                caseRepository,
                evidenceRepository,
                jiraClient,
                evidenceService,
                objectMapper
        );

        dashboardService = new IncidentDashboardService(
                caseRepository,
                evidenceRepository,
                null, // workflowEngine not needed
                objectMapper
        );
    }

    @Test
    void shouldPreserveRawHumanReadableReport() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String turkishReport = """
                # Rovo RCA Analizi
                
                ## Özet
                Veritabanı bağlantısı zaman aşımına uğradı ve NPE hatası oluştu.
                
                ## Detaylar
                RegionService'de NPE hatası tespit edildi.
                
                """;

        String fullComment = turkishReport + """
                REPLAYFIX_ROVO_RCA_V1
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "status": "HYPOTHESIS",
                  "confidence": 0.85,
                  "probableRootCause": "NPE in RegionService"
                }
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), fullComment)
        );

        EvidenceEntity savedEvidence = new EvidenceEntity();
        savedEvidence.setId(UUID.randomUUID());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of());
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(false)))
                .thenReturn(savedEvidence);

        // When
        RovoRcaImportResponse response = importerService.importFromJira(caseId);

        // Then
        assertTrue(response.imported());
        
        // Verify envelope was persisted
        verify(evidenceService).save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), argThat(content -> {
            try {
                RovoRcaEnvelope envelope = objectMapper.readValue((String) content, RovoRcaEnvelope.class);
                assertNotNull(envelope.rawHumanReport());
                assertTrue(envelope.rawHumanReport().contains("Rovo RCA Analizi"));
                assertTrue(envelope.rawHumanReport().contains("Özet"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }), eq(false));
    }

    @Test
    void shouldPreserveRawRovoJson() throws Exception {
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(false)))
                .thenReturn(savedEvidence);

        // When
        importerService.importFromJira(caseId);

        // Then
        verify(evidenceService).save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), argThat(content -> {
            try {
                RovoRcaEnvelope envelope = objectMapper.readValue((String) content, RovoRcaEnvelope.class);
                // Raw JSON should have confidence 1.5 (before normalization)
                assertEquals(1.5, envelope.rawRovoJson().get("confidence").asDouble());
                return true;
            } catch (Exception e) {
                return false;
            }
        }), eq(false));
    }

    @Test
    void shouldCreateNormalizedJson() throws Exception {
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(false)))
                .thenReturn(savedEvidence);

        // When
        importerService.importFromJira(caseId);

        // Then
        verify(evidenceService).save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), argThat(content -> {
            try {
                RovoRcaEnvelope envelope = objectMapper.readValue((String) content, RovoRcaEnvelope.class);
                // Normalized JSON should have confidence clamped to 1.0
                assertEquals(1.0, envelope.normalizedRovoJson().get("confidence").asDouble());
                // Should have default status
                assertEquals("HYPOTHESIS", envelope.normalizedRovoJson().get("status").asText());
                return true;
            } catch (Exception e) {
                return false;
            }
        }), eq(false));
    }

    @Test
    void shouldStoreEnvelopeInContentText() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                Human report
                REPLAYFIX_ROVO_RCA_V1
                {"schemaVersion": "1.0", "jiraKey": "TEST-123", "confidence": 0.8, "probableRootCause": "NPE"}
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(false)))
                .thenReturn(savedEvidence);

        // When
        importerService.importFromJira(caseId);

        // Then
        verify(evidenceService).save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), argThat(content -> {
            try {
                RovoRcaEnvelope envelope = objectMapper.readValue((String) content, RovoRcaEnvelope.class);
                assertEquals(RovoRcaEnvelope.ENVELOPE_SCHEMA_VERSION, envelope.schemaVersion());
                assertEquals(caseId, envelope.caseId());
                assertEquals("TEST-123", envelope.jiraKey());
                assertEquals("10001", envelope.commentId());
                assertEquals("Rovo Bot", envelope.commentAuthor());
                assertEquals("IMPORTED", envelope.importStatus());
                assertNotNull(envelope.rcaStatus());
                return true;
            } catch (Exception e) {
                return false;
            }
        }), eq(false));
    }

    @Test
    void shouldIncludeRawHumanReportInDashboard() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String turkishReport = "# Turkish RCA Report\n\nDetailed analysis...";
        
        RovoRcaEnvelope envelope = RovoRcaEnvelope.create(
                caseId,
                "TEST-123",
                "10001",
                "Rovo Bot",
                turkishReport,
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"confidence\":0.8,\"probableRootCause\":\"NPE\"}",
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"status\":\"HYPOTHESIS\",\"confidence\":0.8,\"probableRootCause\":\"NPE\"}",
                List.of(),
                objectMapper
        );

        String envelopeJson = objectMapper.writeValueAsString(envelope);

        EvidenceEntity rovoEvidence = new EvidenceEntity();
        rovoEvidence.setId(UUID.randomUUID());
        rovoEvidence.setCaseId(caseId);
        rovoEvidence.setEvidenceType(EvidenceType.ROVO_RCA);
        rovoEvidence.setContentText(envelopeJson);
        rovoEvidence.setCreatedAt(Instant.now());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(rovoEvidence));

        // When
        IncidentDashboardView dashboard = dashboardService.getDashboard(caseId);

        // Then
        assertNotNull(dashboard.rovoRca());
        assertEquals("IMPORTED", dashboard.rovoRca().importStatus());
        assertEquals("HYPOTHESIS", dashboard.rovoRca().rcaStatus());
        assertNotNull(dashboard.rovoRca().rawHumanReport());
        assertTrue(dashboard.rovoRca().rawHumanReport().contains("Turkish RCA Report"));
    }

    @Test
    void shouldDuplicateDetectionWorkWithCommentId() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        String comment = """
                REPLAYFIX_ROVO_RCA_V1
                {"schemaVersion": "1.0", "jiraKey": "TEST-123", "confidence": 0.8, "probableRootCause": "NPE"}
                REPLAYFIX_ROVO_RCA_END
                """;

        List<IntegrationModels.JiraComment> comments = List.of(
                new IntegrationModels.JiraComment("10001", "Rovo Bot", Instant.now(), comment)
        );

        // Existing evidence with same commentId
        RovoRcaEnvelope existingEnvelope = RovoRcaEnvelope.create(
                caseId,
                "TEST-123",
                "10001",
                "Rovo Bot",
                "",
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"confidence\":0.8,\"probableRootCause\":\"NPE\"}",
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"status\":\"HYPOTHESIS\",\"confidence\":0.8,\"probableRootCause\":\"NPE\"}",
                List.of(),
                objectMapper
        );
        
        EvidenceEntity existingEvidence = new EvidenceEntity();
        existingEvidence.setId(UUID.randomUUID());
        existingEvidence.setContentText(objectMapper.writeValueAsString(existingEnvelope));

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(jiraClient.getComments("TEST-123")).thenReturn(comments);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(existingEvidence));

        // When
        RovoRcaImportResponse response = importerService.importFromJira(caseId);

        // Then
        assertFalse(response.imported());
        assertEquals(existingEvidence.getId(), response.existingEvidenceId());
        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }
}

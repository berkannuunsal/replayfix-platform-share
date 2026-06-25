package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.*;
import com.etiya.replaylab.repository.*;
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
    private WorkflowRunRepository workflowRunRepository;
    private WorkflowStepRepository workflowStepRepository;
    private EvidenceRepository evidenceRepository;
    private ApprovalRequestRepository approvalRepository;
    private AuditEventRepository auditRepository;
    private ReplayLabWorkflowOrchestrator orchestrator;
    private JiraEvidenceCommentPreviewService previewService;
    private ReplayLabProperties properties;
    private JiraClient jiraClient;
    private EvidenceService evidenceService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        workflowStepRepository = mock(WorkflowStepRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        approvalRepository = mock(ApprovalRequestRepository.class);
        auditRepository = mock(AuditEventRepository.class);
        orchestrator = mock(ReplayLabWorkflowOrchestrator.class);
        previewService = mock(JiraEvidenceCommentPreviewService.class);
        properties = new ReplayLabProperties();
        jiraClient = mock(JiraClient.class);
        evidenceService = mock(EvidenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        importerService = new RovoRcaImporterService(
                caseRepository,
                evidenceRepository,
                jiraClient,
                evidenceService,
                objectMapper
        );

        dashboardService = new IncidentDashboardService(
                caseRepository,
                workflowRunRepository,
                workflowStepRepository,
                evidenceRepository,
                approvalRepository,
                auditRepository,
                orchestrator,
                previewService,
                properties,
                objectMapper,
                new EvidenceSanitizer()
        );

        when(workflowRunRepository.findFirstByCaseIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(approvalRepository.findByCaseIdOrderByRequestedAtDesc(any()))
                .thenReturn(List.of());
        when(auditRepository.findByCaseIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of());
        when(previewService.createPreview(any()))
                .thenThrow(new IllegalStateException("No preview in unit test"));
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
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
        }), eq(true));
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
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
        }), eq(true));
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
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
        }), eq(true));
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
        when(evidenceService.save(eq(caseId), eq(EvidenceType.ROVO_RCA), any(), any(), eq(true)))
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
        }), eq(true));
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
        IncidentDashboardView dashboard = dashboardService.getCaseDashboard(caseId);

        // Then
        assertNotNull(dashboard.rovoRca());
        assertEquals("IMPORTED", dashboard.rovoRca().importStatus());
        assertTrue(dashboard.rovoRca().rovoRcaAvailable());
        assertEquals("HYPOTHESIS", dashboard.rovoRca().rcaStatus());
        assertEquals(0.8, dashboard.rovoRca().rovoConfidence());
        assertEquals("10001", dashboard.rovoRca().commentId());
        assertEquals("Rovo Bot", dashboard.rovoRca().commentAuthor());
        assertNotNull(dashboard.rovoRca().importedAt());
        assertNotNull(dashboard.rovoRca().rawHumanReport());
        assertTrue(dashboard.rovoRca().rawHumanReport().contains("Turkish RCA Report"));
        assertNotNull(dashboard.rovoRca().rawRovoJson());
        assertNotNull(dashboard.rovoRca().normalizedRovoJson());
        assertTrue(dashboard.rovoRca().normalizationWarnings().isEmpty());
    }

    @Test
    void shouldSelectLatestRovoRcaEvidenceForDashboard() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        EvidenceEntity oldEvidence = rovoEvidence(
                caseId,
                "old-comment",
                "Old Bot",
                "Old human report",
                "Old root cause",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of()
        );

        EvidenceEntity latestEvidence = rovoEvidence(
                caseId,
                "new-comment",
                "Rovo Bot",
                "Latest human report",
                "Latest root cause",
                Instant.parse("2026-01-02T00:00:00Z"),
                List.of("Normalized relatedJiraIssues from string to object")
        );

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(oldEvidence, latestEvidence));

        IncidentDashboardView dashboard = dashboardService.getCaseDashboard(caseId);

        assertTrue(dashboard.rovoRca().rovoRcaAvailable());
        assertEquals("new-comment", dashboard.rovoRca().commentId());
        assertEquals("Latest root cause", dashboard.rovoRca().probableRootCause());
        assertTrue(dashboard.rovoRca().rawHumanReport().contains("Latest human report"));
        assertEquals(1, dashboard.rovoRca().normalizationWarnings().size());
    }

    @Test
    void shouldRenderLegacyJsonOnlyRovoRcaOnDashboard() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        EvidenceEntity legacyEvidence = new EvidenceEntity();
        legacyEvidence.setId(UUID.randomUUID());
        legacyEvidence.setCaseId(caseId);
        legacyEvidence.setEvidenceType(EvidenceType.ROVO_RCA);
        legacyEvidence.setContentText("""
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "status": "HYPOTHESIS",
                  "confidence": 0.45,
                  "probableRootCause": "Legacy JSON root cause"
                }
                """);
        legacyEvidence.setCreatedAt(Instant.now());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(legacyEvidence));

        IncidentDashboardView dashboard = dashboardService.getCaseDashboard(caseId);

        assertTrue(dashboard.rovoRca().rovoRcaAvailable());
        assertEquals("IMPORTED", dashboard.rovoRca().importStatus());
        assertEquals("HYPOTHESIS", dashboard.rovoRca().rcaStatus());
        assertEquals(0.45, dashboard.rovoRca().rovoConfidence());
        assertEquals("Legacy JSON root cause", dashboard.rovoRca().probableRootCause());
        assertNotNull(dashboard.rovoRca().rawRovoJson());
        assertNotNull(dashboard.rovoRca().normalizedRovoJson());
    }

    @Test
    void shouldRedactSecretsFromRovoRcaDashboard() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        EvidenceEntity legacyEvidence = new EvidenceEntity();
        legacyEvidence.setId(UUID.randomUUID());
        legacyEvidence.setCaseId(caseId);
        legacyEvidence.setEvidenceType(EvidenceType.ROVO_RCA);
        legacyEvidence.setContentText("""
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "status": "HYPOTHESIS",
                  "confidence": 0.2,
                  "probableRootCause": "Authorization: Bearer abc123 leaked in logs",
                  "authorization": "Bearer abc123",
                  "nested": { "apiToken": "secret-token" }
                }
                """);
        legacyEvidence.setCreatedAt(Instant.now());

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(legacyEvidence));

        IncidentDashboardView dashboard = dashboardService.getCaseDashboard(caseId);
        String dashboardJson = dashboard.rovoRca().rawRovoJson().toString()
                + dashboard.rovoRca().probableRootCause();

        assertFalse(dashboardJson.contains("abc123"));
        assertFalse(dashboardJson.contains("secret-token"));
        assertTrue(dashboardJson.contains("[REDACTED]"));
    }

    private EvidenceEntity rovoEvidence(
            UUID caseId,
            String commentId,
            String commentAuthor,
            String rawHumanReport,
            String probableRootCause,
            Instant createdAt,
            List<String> warnings
    ) throws Exception {
        RovoRcaEnvelope envelope = RovoRcaEnvelope.create(
                caseId,
                "TEST-123",
                commentId,
                commentAuthor,
                rawHumanReport,
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"confidence\":0.8,\"probableRootCause\":\""
                        + probableRootCause
                        + "\"}",
                "{\"schemaVersion\":\"1.0\",\"jiraKey\":\"TEST-123\",\"status\":\"HYPOTHESIS\",\"confidence\":0.8,\"probableRootCause\":\""
                        + probableRootCause
                        + "\"}",
                warnings,
                objectMapper
        );

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.ROVO_RCA);
        evidence.setContentText(objectMapper.writeValueAsString(envelope));
        evidence.setCreatedAt(createdAt);
        return evidence;
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

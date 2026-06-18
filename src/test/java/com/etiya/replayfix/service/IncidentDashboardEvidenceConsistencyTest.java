package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.ApprovalStatus;
import com.etiya.replayfix.domain.ApprovalTargetType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.DashboardEvidenceCard;
import com.etiya.replayfix.model.IncidentDashboardView;
import com.etiya.replayfix.repository.ApprovalRequestRepository;
import com.etiya.replayfix.repository.AuditEventRepository;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.WorkflowRunRepository;
import com.etiya.replayfix.repository.WorkflowStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentDashboardEvidenceConsistencyTest {

    private IncidentDashboardService service;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ApprovalRequestRepository approvalRepository;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        WorkflowRunRepository workflowRunRepository =
                mock(WorkflowRunRepository.class);
        WorkflowStepRepository workflowStepRepository =
                mock(WorkflowStepRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        approvalRepository = mock(ApprovalRequestRepository.class);
        AuditEventRepository auditRepository =
                mock(AuditEventRepository.class);
        ReplayFixWorkflowOrchestrator orchestrator =
                mock(ReplayFixWorkflowOrchestrator.class);
        JiraEvidenceCommentPreviewService previewService =
                mock(JiraEvidenceCommentPreviewService.class);

        service = new IncidentDashboardService(
                caseRepository,
                workflowRunRepository,
                workflowStepRepository,
                evidenceRepository,
                approvalRepository,
                auditRepository,
                orchestrator,
                previewService,
                new ReplayFixProperties(),
                new ObjectMapper().findAndRegisterModules(),
                new EvidenceSanitizer()
        );

        when(workflowRunRepository.findFirstByCaseIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByCaseIdOrderByRequestedAtDesc(any()))
                .thenReturn(List.of());
        when(auditRepository.findByCaseIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of());
        when(previewService.createPreview(any()))
                .thenThrow(new IllegalStateException("Preview not needed"));
    }

    @Test
    void shouldBuildEvidenceMatrixFromActualEvidenceTypes() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity replayCase = caseEntity(caseId);

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.JIRA_ISSUE, "jira", "{}"),
                evidence(caseId, EvidenceType.REPOSITORY_RESOLUTION,
                        "repository-resolution",
                        "{\"repository\":\"DCE/backend\",\"branch\":\"test2\"}"),
                evidence(caseId, EvidenceType.INCIDENT_VERSION,
                        "incident-version",
                        "{\"exactMatch\":true}"),
                evidence(caseId, EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-build",
                        "{\"jobName\":\"MODERNIZATION.BACKEND_BUILD_12\",\"buildNumber\":3066}"),
                evidence(caseId, EvidenceType.LOKI_LOG,
                        "loki-log",
                        "{\"matchedRowCount\":0}"),
                evidence(caseId, EvidenceType.TEMPO_ENRICHMENT,
                        "tempo-enrichment",
                        "{\"foundTraceCount\":0}"),
                evidence(caseId, EvidenceType.SOURCE_CONTEXT,
                        "source-context",
                        "{\"matchedFileCount\":0}"),
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        "{\"normalizedRovoJson\":{\"probableRootCause\":\"Rovo RCA\"}}")
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(replayCase));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                any(EvidenceType.class)
        )).thenAnswer(invocation -> {
            EvidenceType type = invocation.getArgument(1);
            return evidence.stream()
                    .filter(item -> item.getEvidenceType() == type)
                    .toList();
        });

        IncidentDashboardView dashboard = service.getCaseDashboard(caseId);

        assertEquals("CONFIRMED", card(dashboard, "Jira").status());
        assertEquals("CONFIRMED", card(dashboard, "Bitbucket").status());
        assertEquals("CONFIRMED", card(dashboard, "Jenkins").status());
        assertEquals("PROBABLE", card(dashboard, "Loki").status());
        assertEquals("PROBABLE", card(dashboard, "Tempo").status());
        assertEquals("PROBABLE", card(dashboard, "Source Context").status());
        assertEquals("CONFIRMED", card(dashboard, "ReplayFix").status());
    }

    @Test
    void shouldUseRovoRcaForRootCauseSummaryWhenAvailable() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity replayCase = caseEntity(caseId);

        String rovoRootCause =
                "Kullanıcı arayüzünden gelen hatalı parametreler veya eksik API validasyonları.";
        String fixDirection =
                "API seviyesinde region ve tax_info parametreleri için çapraz validasyon eklenmesi";

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                        "deterministic-root-cause",
                        "{\"probableCause\":\"Deterministic fallback\",\"confidence\":0.6}"),
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "normalizedRovoJson": {
                                    "status": "HYPOTHESIS",
                                    "confidence": 0.2,
                                    "probableRootCause": "%s",
                                    "minimumFixDirection": ["%s"]
                                  }
                                }
                                """.formatted(rovoRootCause, fixDirection))
        );

        stubDashboardEvidence(caseId, replayCase, evidence);

        IncidentDashboardView dashboard = service.getCaseDashboard(caseId);

        assertEquals(rovoRootCause, dashboard.rootCause().probableRootCause());
        assertEquals(0.2, dashboard.rootCause().confidence());
        assertEquals("LOW", dashboard.rootCause().confidenceBand());
        assertEquals("Rovo RCA enriched analysis",
                dashboard.rootCause().analysisType());
        assertTrue(dashboard.rootCause().recommendedFixDirection()
                .contains(fixDirection));
        assertFalse(dashboard.rootCause().probableRootCause()
                .contains("Deterministic analysis pending"));
    }

    @Test
    void shouldFallbackToDeterministicRootCauseWhenRovoMissing() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity replayCase = caseEntity(caseId);

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                        "deterministic-root-cause",
                        "{\"probableCause\":\"Deterministic fallback\",\"confidence\":0.55}")
        );

        stubDashboardEvidence(caseId, replayCase, evidence);

        IncidentDashboardView dashboard = service.getCaseDashboard(caseId);

        assertEquals("Deterministic fallback",
                dashboard.rootCause().probableRootCause());
        assertEquals(0.55, dashboard.rootCause().confidence());
        assertEquals("ReplayFix deterministic RCA",
                dashboard.rootCause().analysisType());
        assertFalse(dashboard.rootCause().probableRootCause()
                .contains("Deterministic analysis pending"));
    }

    @Test
    void shouldShowPendingFailingRegressionTestDraftApprovalOnDashboard() {
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity replayCase = caseEntity(caseId);
        UUID evidenceId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(
                        caseId,
                        EvidenceType.FAILING_REGRESSION_TEST_DRAFT,
                        "failing-regression-test-draft",
                        "{\"status\":\"DRAFT\",\"fileWritten\":false}"
                )
        );

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(UUID.randomUUID());
        approval.setCaseId(caseId);
        approval.setTargetType(ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT);
        approval.setTargetEvidenceId(evidenceId);
        approval.setTargetEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name());
        approval.setTargetEvidenceSource("failing-regression-test-draft");
        approval.setStatus(ApprovalStatus.PENDING);
        approval.setRequestedBy("Berkan Unsal");
        approval.setRequestedAt(Instant.now());

        stubDashboardEvidence(caseId, replayCase, evidence);
        when(approvalRepository.findByCaseIdOrderByRequestedAtDesc(caseId))
                .thenReturn(List.of(approval));

        IncidentDashboardView dashboard = service.getCaseDashboard(caseId);

        assertEquals(1, dashboard.approvals().size());
        assertEquals(
                ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT,
                dashboard.approvals().get(0).targetType()
        );
        assertEquals(ApprovalStatus.PENDING, dashboard.approvals().get(0).status());
        assertFalse(dashboard.approvals().get(0).allowsGeneratedTestWrite());
    }

    private void stubDashboardEvidence(
            UUID caseId,
            ReplayCaseEntity replayCase,
            List<EvidenceEntity> evidence
    ) {
        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(replayCase));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                any(EvidenceType.class)
        )).thenAnswer(invocation -> {
            EvidenceType type = invocation.getArgument(1);
            return evidence.stream()
                    .filter(item -> item.getEvidenceType() == type)
                    .toList();
        });
    }

    private DashboardEvidenceCard card(
            IncidentDashboardView dashboard,
            String source
    ) {
        return dashboard.evidenceCards()
                .stream()
                .filter(item -> source.equals(item.source()))
                .findFirst()
                .orElseThrow();
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        assertNotNull(replayCase.getId());
        return replayCase;
    }

    private EvidenceEntity evidence(
            UUID caseId,
            EvidenceType type,
            String source,
            String contentText
    ) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(type);
        evidence.setSource(source);
        evidence.setContentText(contentText);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

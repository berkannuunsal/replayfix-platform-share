package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.FailingRegressionTestDraftResult;
import com.etiya.replayfix.model.RegressionTestHypothesis;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FailingRegressionTestDraftServiceTest {

    private FailingRegressionTestDraftService service;
    private EvidenceRepository evidenceRepository;
    private EvidenceService evidenceService;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        evidenceRepository = mock(EvidenceRepository.class);
        evidenceService = mock(EvidenceService.class);
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        service = new FailingRegressionTestDraftService(
                evidenceRepository,
                evidenceService,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldGenerateDraftFromRegressionTestHypothesis() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity hypothesisEvidence = hypothesisEvidence(caseId);
        EvidenceEntity saved = new EvidenceEntity();
        saved.setId(UUID.randomUUID());
        saved.setCaseId(caseId);
        saved.setEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT);

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of(hypothesisEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.FAILING_REGRESSION_TEST_DRAFT))
                .thenReturn(List.of());
        when(evidenceService.save(
                eq(caseId),
                eq(EvidenceType.FAILING_REGRESSION_TEST_DRAFT),
                eq("failing-regression-test-draft"),
                any(),
                eq(true)
        )).thenReturn(saved);

        FailingRegressionTestDraftResult result = service.generate(caseId, false);

        assertTrue(result.generated());
        assertEquals(saved.getId(), result.evidenceId());
        assertEquals("DRAFT", result.draft().status());
        assertEquals("NEEDS_HUMAN_COMPLETION", result.draft().readiness());
        assertFalse(result.draft().fileWritten());
        assertFalse(result.draft().testExecuted());
        assertTrue(result.draft().humanApprovalRequired());
        assertTrue(result.draft().sourceCode().contains("@Disabled"));
        assertTrue(result.draft().sourceCode().contains("fail("));
        assertTrue(result.draft().proposedRelativePath().startsWith("src/test/java/"));
        assertNotNull(result.draft().contentSha256());
    }

    @Test
    void shouldReturnExistingDraftWhenForceIsFalse() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity hypothesisEvidence = hypothesisEvidence(caseId);
        EvidenceEntity existing = new EvidenceEntity();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(caseId);
        existing.setEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT);
        existing.setContentText("""
                {
                  "schemaVersion": "1.0",
                  "caseId": "%s",
                  "hypothesisEvidenceId": "%s",
                  "jiraKey": "TEST-123",
                  "source": "failing-regression-test-draft",
                  "generatedAt": "2026-01-01T00:00:00Z",
                  "status": "DRAFT",
                  "readiness": "NEEDS_HUMAN_COMPLETION",
                  "testType": "API_REGRESSION",
                  "proposedRelativePath": "src/test/java/com/etiya/replayfix/generated/TestFailingRegressionTest.java",
                  "proposedPackage": "com.etiya.replayfix.generated",
                  "proposedClassName": "TestFailingRegressionTest",
                  "proposedMethodName": "shouldReproduceIncident",
                  "language": "JAVA",
                  "framework": "JUnit 5",
                  "sourceCode": "class Test {}",
                  "contentSha256": "abc",
                  "targetFlow": "flow",
                  "probableRootCause": "root",
                  "expectedFailureSignals": [],
                  "assertions": [],
                  "sourceEvidence": [],
                  "assumptions": [],
                  "warnings": [],
                  "fileWritten": false,
                  "testExecuted": false,
                  "writeAuthorized": false,
                  "executionAuthorized": false,
                  "humanApprovalRequired": true
                }
                """.formatted(caseId, hypothesisEvidence.getId()));

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of(hypothesisEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.FAILING_REGRESSION_TEST_DRAFT))
                .thenReturn(List.of(existing));

        FailingRegressionTestDraftResult result = service.generate(caseId, false);

        assertFalse(result.generated());
        assertEquals(existing.getId(), result.existingEvidenceId());
        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldReplaceExistingDraftWhenForceIsTrue() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity hypothesisEvidence = hypothesisEvidence(caseId);
        EvidenceEntity existing = new EvidenceEntity();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(caseId);
        existing.setEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT);
        existing.setContentText("{}");

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of(hypothesisEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.FAILING_REGRESSION_TEST_DRAFT))
                .thenReturn(List.of(existing));
        when(evidenceService.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evidenceService.hashContent(any())).thenReturn("hash");
        when(evidenceRepository.save(existing)).thenReturn(existing);

        FailingRegressionTestDraftResult result = service.generate(caseId, true);

        assertTrue(result.generated());
        assertEquals(existing.getId(), result.evidenceId());
        assertTrue(existing.getContentText().contains("\"status\":\"DRAFT\""));
        assertEquals("hash", existing.getContentHash());
        verify(evidenceRepository).save(existing);
    }

    private EvidenceEntity hypothesisEvidence(UUID caseId) throws Exception {
        RegressionTestHypothesis hypothesis = new RegressionTestHypothesis(
                RegressionTestHypothesis.SCHEMA_VERSION,
                caseId,
                "TEST-123",
                "regression-test-hypothesis",
                Instant.now(),
                "HYPOTHESIS",
                "API_REGRESSION",
                "Billing Account Creation / Update Flow",
                "bss-backend",
                "Missing API validation",
                0.2,
                "Reproduce invalid billing account parameter handling.",
                List.of("Use incident version"),
                List.of("Invalid BC/ON parameter combination"),
                List.of("Invalid state/tax info is persisted"),
                List.of("Assert invalid input is rejected"),
                List.of("Mock downstream billing dependency"),
                List.of("No trace evidence"),
                List.of("ROVO_RCA:abc"),
                Map.of("commentId", "10001"),
                false,
                false,
                false,
                false,
                true,
                List.of("Low confidence")
        );

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.REGRESSION_TEST_HYPOTHESIS);
        evidence.setSource("regression-test-hypothesis");
        evidence.setCreatedAt(Instant.now());
        evidence.setContentText(objectMapper.writeValueAsString(hypothesis));
        return evidence;
    }
}

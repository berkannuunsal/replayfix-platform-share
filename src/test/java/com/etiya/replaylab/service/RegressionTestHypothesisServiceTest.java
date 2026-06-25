package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.RegressionTestHypothesisResult;
import com.etiya.replaylab.model.RovoRcaEnvelope;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RegressionTestHypothesisServiceTest {

    private RegressionTestHypothesisService service;
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

        service = new RegressionTestHypothesisService(
                evidenceRepository,
                evidenceService,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldGenerateHypothesisFromRovoEnvelope() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity rovoEvidence = rovoEvidence(caseId);
        EvidenceEntity saved = new EvidenceEntity();
        saved.setId(UUID.randomUUID());
        saved.setCaseId(caseId);
        saved.setEvidenceType(EvidenceType.REGRESSION_TEST_HYPOTHESIS);

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(rovoEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of());
        when(evidenceService.save(
                eq(caseId),
                eq(EvidenceType.REGRESSION_TEST_HYPOTHESIS),
                eq("regression-test-hypothesis"),
                any(),
                eq(true)
        )).thenReturn(saved);

        RegressionTestHypothesisResult result = service.generate(caseId, false);

        assertTrue(result.generated());
        assertEquals(saved.getId(), result.evidenceId());
        assertEquals("API_REGRESSION", result.hypothesis().testType());
        assertFalse(result.hypothesis().fileWritten());
        assertFalse(result.hypothesis().testExecuted());
        assertFalse(result.hypothesis().writeAuthorized());
        assertFalse(result.hypothesis().executionAuthorized());
        assertTrue(result.hypothesis().humanApprovalRequired());
        assertTrue(result.hypothesis().sourceEvidence().stream()
                .anyMatch(item -> item.startsWith("ROVO_RCA:")));
        assertTrue(result.hypothesis().assertions().stream()
                .anyMatch(item -> item.contains("minimum fix direction")));
    }

    @Test
    void shouldReturnExistingHypothesisWhenForceIsFalse() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity rovoEvidence = rovoEvidence(caseId);

        EvidenceEntity existing = new EvidenceEntity();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(caseId);
        existing.setEvidenceType(EvidenceType.REGRESSION_TEST_HYPOTHESIS);
        existing.setContentText("""
                {
                  "schemaVersion": "1.0",
                  "caseId": "%s",
                  "jiraKey": "TEST-123",
                  "source": "regression-test-hypothesis",
                  "generatedAt": "2026-01-01T00:00:00Z",
                  "status": "HYPOTHESIS",
                  "testType": "API_REGRESSION",
                  "targetFlow": "flow",
                  "targetComponent": "backend",
                  "probableRootCause": "root",
                  "confidence": 0.2,
                  "failingScenario": "scenario",
                  "preconditions": [],
                  "suggestedInputs": [],
                  "expectedFailureSignals": [],
                  "assertions": [],
                  "mocksOrDependencies": [],
                  "missingEvidence": [],
                  "sourceEvidence": [],
                  "rovoSummary": {},
                  "fileWritten": false,
                  "testExecuted": false,
                  "writeAuthorized": false,
                  "executionAuthorized": false,
                  "humanApprovalRequired": true,
                  "warnings": []
                }
                """.formatted(caseId));

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(rovoEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of(existing));

        RegressionTestHypothesisResult result = service.generate(caseId, false);

        assertFalse(result.generated());
        assertEquals(existing.getId(), result.existingEvidenceId());
        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldReplaceExistingHypothesisWhenForceIsTrue() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceEntity rovoEvidence = rovoEvidence(caseId);

        EvidenceEntity existing = new EvidenceEntity();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(caseId);
        existing.setEvidenceType(EvidenceType.REGRESSION_TEST_HYPOTHESIS);
        existing.setContentText("{}");

        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA))
                .thenReturn(List.of(rovoEvidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS))
                .thenReturn(List.of(existing));
        when(evidenceService.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evidenceService.hashContent(any())).thenReturn("hash");
        when(evidenceRepository.save(existing)).thenReturn(existing);

        RegressionTestHypothesisResult result = service.generate(caseId, true);

        assertTrue(result.generated());
        assertEquals(existing.getId(), result.evidenceId());
        assertTrue(existing.getContentText().contains("\"humanApprovalRequired\":true"));
        assertEquals("hash", existing.getContentHash());
        verify(evidenceRepository).save(existing);
    }

    private EvidenceEntity rovoEvidence(UUID caseId) throws Exception {
        String rawJson = """
                {
                  "schemaVersion": "1.0",
                  "jiraKey": "TEST-123",
                  "status": "HYPOTHESIS",
                  "affectedFlow": "backend API order validation flow",
                  "technicalSymptom": "API accepts invalid order parameter",
                  "impactedComponent": "backend",
                  "probableRootCause": "Missing API validation",
                  "confidence": 0.2,
                  "regressionTestHypothesis": ["Call the API with the invalid parameter observed in Jira"],
                  "minimumFixDirection": ["Add deterministic validation before downstream processing"],
                  "evidenceMatrix": [{"category": "jira", "status": "PRESENT", "references": ["comment"], "reason": "Rovo RCA"}],
                  "missingEvidence": ["No production replay yet"]
                }
                """;

        RovoRcaEnvelope envelope = RovoRcaEnvelope.create(
                caseId,
                "TEST-123",
                "10001",
                "Rovo Bot",
                "Human report",
                rawJson,
                rawJson,
                List.of(),
                objectMapper
        );

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.ROVO_RCA);
        evidence.setSource("rovo-incident-commander");
        evidence.setCreatedAt(Instant.now());
        evidence.setContentText(objectMapper.writeValueAsString(envelope));
        return evidence;
    }
}

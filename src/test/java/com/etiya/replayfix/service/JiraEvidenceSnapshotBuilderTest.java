package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceAvailability;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.JiraEvidenceMatrixItem;
import com.etiya.replayfix.model.JiraEvidenceSnapshot;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JiraEvidenceSnapshotBuilderTest {

    private JiraEvidenceSnapshotBuilder builder;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        builder = new JiraEvidenceSnapshotBuilder(
                caseRepository,
                evidenceRepository,
                new EvidenceSanitizer(),
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void shouldBuildFreshSnapshotPreviewFromCurrentEvidence() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.JIRA_ISSUE,
                        "jira",
                        "{\"summary\":\"Billing account state mismatch\"}"),
                evidence(caseId, EvidenceType.REPOSITORY_RESOLUTION,
                        "repository-resolution",
                        "{\"repository\":\"DCE/backend\",\"branch\":\"test2\"}"),
                evidence(caseId, EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-build",
                        "{\"jobName\":\"MODERNIZATION.BACKEND_BUILD_12\",\"buildNumber\":3066}"),
                evidence(caseId, EvidenceType.INCIDENT_VERSION,
                        "incident-version",
                        "{\"exactMatch\":true,\"commitSha\":\"7b65a116\"}"),
                evidence(caseId, EvidenceType.LOKI_LOG,
                        "loki-log",
                        "{\"matchedRowCount\":0}"),
                evidence(caseId, EvidenceType.TEMPO_ENRICHMENT,
                        "tempo-enrichment",
                        "{\"foundTraceCount\":0}"),
                evidence(caseId, EvidenceType.SOURCE_CONTEXT,
                        "source-context",
                        "{\"matchedFileCount\":0}"),
                evidence(caseId, EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                        "deterministic-root-cause",
                        "{\"status\":\"HYPOTHESIS\",\"probableRootCause\":\"Evidence-only deterministic hypothesis\",\"confidence\":0.42}"),
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "importStatus": "IMPORTED",
                                  "rcaStatus": "HYPOTHESIS",
                                  "normalizedRovoJson": {
                                    "probableRootCause": "Rovo enriched hypothesis",
                                    "confidence": 0.2,
                                    "minimumFixDirection": "Validate region and tax_info together"
                                  }
                                }
                                """),
                evidence(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS,
                        "regression-test-hypothesis",
                        "{\"targetFlow\":\"Billing Account Creation / Update Flow\",\"failingScenario\":\"Reproduce billing mismatch\"}")
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        JiraEvidenceSnapshot snapshot = builder.build(caseId);

        assertEquals("Evidence-only deterministic hypothesis",
                snapshot.probableRootCause());
        assertEquals(0.42, snapshot.rootCauseConfidence());
        assertFalse(snapshot.missingEvidence()
                .contains("Jira issue details not collected"));
        assertFalse(snapshot.probableRootCause()
                .contains("Deterministic analysis pending"));

        String chain = String.join(" | ", snapshot.probableFailureChain());
        assertTrue(chain.contains("DCE/backend"));
        assertTrue(chain.contains("test2"));
        assertTrue(chain.contains("MODERNIZATION.BACKEND_BUILD_12"));
        assertTrue(chain.contains("3066"));
        assertTrue(chain.contains("Incident version validation available"));
        assertTrue(chain.contains("Rovo RCA imported"));
        assertTrue(chain.contains("Regression test hypothesis generated"));

        assertEquals(EvidenceAvailability.CONFIRMED,
                matrix(snapshot, "JENKINS").status());
        assertEquals(EvidenceAvailability.PROBABLE,
                matrix(snapshot, "LOKI").status());
        assertEquals(EvidenceAvailability.PROBABLE,
                matrix(snapshot, "TEMPO").status());
        assertEquals(EvidenceAvailability.PROBABLE,
                matrix(snapshot, "SOURCE_CONTEXT").status());

        String sources = snapshot.evidenceMatrix()
                .stream()
                .map(JiraEvidenceMatrixItem::source)
                .collect(Collectors.joining(","));
        assertTrue(sources.contains("BITBUCKET"));
        assertTrue(sources.contains("REPLAYFIX"));
    }

    private JiraEvidenceMatrixItem matrix(
            JiraEvidenceSnapshot snapshot,
            String source
    ) {
        return snapshot.evidenceMatrix()
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

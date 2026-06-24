package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotJiraPreviewResponse;
import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotResponse;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldenPathEvidenceSnapshotServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private GoldenPathEvidenceSnapshotService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new GoldenPathEvidenceSnapshotService(
                caseRepository,
                evidenceRepository,
                objectMapper
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
    }

    @Test
    void snapshotIsReadyWhenCanonicalEvidenceExists() {
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(canonicalEvidence(true, true));

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("READY");
        assertThat(response.sourceContractValidation().valid()).isTrue();
        assertThat(response.repository().projectKey()).isEqualTo("DCE");
        assertThat(response.jenkins().jobName())
                .isEqualTo("MODERNIZATION.BACKEND_BUILD_12");
        assertThat(response.incidentVersion().evidenceAvailable()).isTrue();
        assertThat(response.aiInputBundle().source())
                .isEqualTo("replayfix-ai-bundle-builder");
    }

    @Test
    void snapshotIsPartialWhenOptionalLokiTempoEvidenceIsMissing() {
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(canonicalEvidence(false, false));

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("PARTIAL");
        assertThat(response.warnings()).contains(
                "LOKI_EVIDENCE_MISSING",
                "TEMPO_EVIDENCE_MISSING"
        );
        assertThat(response.sourceContractValidation().valid()).isTrue();
    }

    @Test
    void snapshotIsPartialWhenCanonicalAiInputBundleIsMissing() {
        List<EvidenceEntity> evidence = canonicalEvidence(true, true).stream()
                .filter(item -> item.getEvidenceType()
                        != EvidenceType.AI_INPUT_BUNDLE)
                .filter(item -> item.getEvidenceType()
                        != EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("PARTIAL");
        assertThat(response.sourceContractValidation().valid()).isTrue();
        assertThat(response.warnings()).contains("AI_INPUT_BUNDLE_MISSING");
        assertThat(response.blockers()).doesNotContain("AI_INPUT_BUNDLE_MISSING");
    }

    @Test
    void wrongJenkinsSourceBlocksSnapshot() {
        List<EvidenceEntity> evidence = canonicalEvidence(true, true).stream()
                .filter(item -> item.getEvidenceType()
                        != EvidenceType.JENKINS_BUILD_CONTEXT)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        evidence.add(evidence(
                EvidenceType.JENKINS_BUILD_CONTEXT,
                "wrong-source",
                jenkinsJson()
        ));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("BLOCKED");
        assertThat(response.blockers()).contains("SOURCE_CONTRACT_MISMATCH");
        assertThat(response.sourceContractValidation().errors())
                .anySatisfy(error -> assertThat(error)
                        .contains("JENKINS_BUILD_CONTEXT"));
    }

    @Test
    void wrongIncidentVersionSourceBlocksSnapshot() {
        List<EvidenceEntity> evidence = canonicalEvidence(true, true).stream()
                .filter(item -> item.getEvidenceType()
                        != EvidenceType.INCIDENT_VERSION)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        evidence.add(evidence(
                EvidenceType.INCIDENT_VERSION,
                "wrong-source",
                incidentJson()
        ));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("BLOCKED");
        assertThat(response.sourceContractValidation().errors())
                .anySatisfy(error -> assertThat(error)
                        .contains("INCIDENT_VERSION"));
    }

    @Test
    void wrongAiInputBundleSourceBlocksSnapshot() {
        List<EvidenceEntity> evidence = canonicalEvidence(true, true).stream()
                .filter(item -> item.getEvidenceType()
                        != EvidenceType.AI_INPUT_BUNDLE)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        evidence.add(evidence(
                EvidenceType.AI_INPUT_BUNDLE,
                "legacy-ai-bundle",
                "{\"bundleVersion\":\"legacy\"}"
        ));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.snapshotStatus()).isEqualTo("BLOCKED");
        assertThat(response.sourceContractValidation().errors())
                .anySatisfy(error -> assertThat(error)
                        .contains("AI_INPUT_BUNDLE"));
    }

    @Test
    void markdownAndRovoBlockAreSafeAndUseful() {
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(canonicalEvidence(true, true));

        GoldenPathEvidenceSnapshotResponse response =
                service.snapshot(caseId, false, true, true, true);

        assertThat(response.jiraMarkdownPreview())
                .contains("ReplayFix Evidence Snapshot")
                .contains("Repository:")
                .contains("Jenkins:")
                .contains("Incident Version:")
                .contains("Deterministic RCA:");
        assertThat(response.rovoRcaInputBlock())
                .contains("REPLAYFIX_ROVO_RCA_V1")
                .contains("Region validation mismatch hypothesis");
        assertThat(asJson(response))
                .doesNotContain("rawProductionPayload")
                .doesNotContain("reasoning_content")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token");
    }

    @Test
    void jiraPreviewDoesNotPostAnything() {
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(canonicalEvidence(true, true));

        GoldenPathEvidenceSnapshotJiraPreviewResponse response =
                service.jiraPreview(caseId);

        assertThat(response.safeToPost()).isTrue();
        assertThat(response.preview()).contains("ReplayFix Evidence Snapshot");
        verify(evidenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private List<EvidenceEntity> canonicalEvidence(
            boolean includeLoki,
            boolean includeTempo
    ) {
        java.util.ArrayList<EvidenceEntity> values = new java.util.ArrayList<>();
        values.add(evidence(
                EvidenceType.REPOSITORY_RESOLUTION,
                "repository-resolution",
                "{\"projectKey\":\"DCE\",\"primaryRepositorySlug\":\"backend\",\"sourceBranch\":\"test2\"}"
        ));
        values.add(evidence(
                EvidenceType.JENKINS_BUILD_CONTEXT,
                "jenkins-evidence-collector",
                jenkinsJson()
        ));
        values.add(evidence(
                EvidenceType.INCIDENT_VERSION,
                "jenkins-incident-version-validator",
                incidentJson()
        ));
        values.add(evidence(
                EvidenceType.AI_INPUT_BUNDLE,
                "replayfix-ai-bundle-builder",
                "{\"bundleVersion\":\"replayfix-ai-bundle-v1\",\"includedEvidence\":[\"JENKINS_BUILD_CONTEXT\"]}"
        ));
        values.add(evidence(
                EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                "deterministic-root-cause",
                """
                        {
                          "jiraKey":"FIZZMS-8346",
                          "status":"HYPOTHESIS",
                          "classification":"VALIDATION_GUARD",
                          "probableCause":"Region validation mismatch hypothesis",
                          "confidence":0.72,
                          "affectedApplications":["backend"],
                          "supportingEvidence":["Jenkins commit matched incident version"],
                          "missingEvidence":[],
                          "recommendedActions":["Replay in isolated environment"]
                        }
                        """
        ));
        if (includeLoki) {
            values.add(evidence(
                    EvidenceType.LOKI_LOG,
                    "loki",
                    "{\"logs\":[{\"line\":\"traceId=abc123 failure\"}]}"
            ));
        }
        if (includeTempo) {
            values.add(evidence(
                    EvidenceType.TEMPO_TRACE,
                    "tempo",
                    "{\"traceId\":\"abc123\"}"
            ));
        }
        return values;
    }

    private String jenkinsJson() {
        return """
                {
                  "applicationKey":"backend",
                  "repositorySlug":"backend",
                  "build":{
                    "jobName":"MODERNIZATION.BACKEND_BUILD_12",
                    "buildNumber":3056,
                    "result":"SUCCESS",
                    "url":"https://jenkins.example/job/3056",
                    "commitSha":"330d124e1d491393804f40d610687b35e0e75d38"
                  }
                }
                """;
    }

    private String incidentJson() {
        return """
                {
                  "repositorySlug":"backend",
                  "branch":"test2",
                  "strategy":"JENKINS_VALIDATED",
                  "resolvedCommitSha":"330d124e1d491393804f40d610687b35e0e75d38",
                  "resolvedTag":"backend-3056",
                  "exactMatch":true
                }
                """;
    }

    private EvidenceEntity evidence(
            EvidenceType type,
            String source,
            String content
    ) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setEvidenceType(type);
        entity.setSource(source);
        entity.setContentText(content);
        entity.setSanitized(true);
        entity.setCreatedAt(Instant.now().plusMillis(type.ordinal()));
        return entity;
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-8346");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.CONTEXT_READY);
        entity.setSynthetic(false);
        entity.setTraceId("abc123");
        return entity;
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

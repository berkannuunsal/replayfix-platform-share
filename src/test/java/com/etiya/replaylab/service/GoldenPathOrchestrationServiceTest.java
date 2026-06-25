package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldenPathOrchestrationServiceTest {

    @Test
    void aiInputBundleMissingIncidentVersionReturnsPartialWarning()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
        AiInputBundleRefreshService aiInputBundleRefreshService =
                mock(AiInputBundleRefreshService.class);
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());
        when(aiInputBundleRefreshService.refresh(caseId)).thenThrow(
                new IllegalStateException(
                        "Required evidence not found. type=INCIDENT_VERSION, source=null"
                )
        );
        GoldenPathOrchestrationService service = service(
                evidenceRepository,
                aiInputBundleRefreshService
        );

        Map<String, Object> step = invokeCollectAiInputBundle(service, caseId);

        assertThat(step.get("result")).isEqualTo("PARTIAL");
        assertThat(step.get("bundleCreated")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) step.get("warnings");
        assertThat(warnings)
                .contains(GoldenPathOrchestrationService
                        .INCIDENT_VERSION_EVIDENCE_MISSING);
    }

    @Test
    void overallStatusDoesNotFailWhenIncidentVersionIsMissing()
            throws Exception {
        GoldenPathOrchestrationService service = service(
                mock(EvidenceRepository.class),
                mock(AiInputBundleRefreshService.class)
        );
        Map<String, Object> steps = Map.of(
                "0_target_validation", Map.of("result", "SUCCESS"),
                "1_case_resolution", Map.of("result", "SUCCESS"),
                "2_jira_evidence", Map.of("result", "SUCCESS"),
                "3_repository_resolution", Map.of("result", "SUCCESS"),
                "4_jenkins_evidence", Map.of("result", "SUCCESS"),
                "5_incident_version", Map.of(
                        "result", "FAILED",
                        "error", "INCIDENT_VERSION evidence missing"
                ),
                "6_context_collection", Map.of("result", "SUCCESS"),
                "7_loki_evidence", Map.of("result", "SUCCESS"),
                "9_ai_input_bundle", Map.of(
                        "result", "PARTIAL",
                        "warnings", List.of(GoldenPathOrchestrationService
                                .INCIDENT_VERSION_EVIDENCE_MISSING)
                )
        );

        String status = invokeCalculateOverallStatus(service, steps);

        assertThat(status).isEqualTo("PARTIAL_SUCCESS");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeCollectAiInputBundle(
            GoldenPathOrchestrationService service,
            UUID caseId
    ) throws Exception {
        Method method = GoldenPathOrchestrationService.class
                .getDeclaredMethod("collectAiInputBundle", UUID.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, caseId);
    }

    @SuppressWarnings("unchecked")
    private String invokeCalculateOverallStatus(
            GoldenPathOrchestrationService service,
            Map<String, Object> steps
    ) throws Exception {
        Method method = GoldenPathOrchestrationService.class
                .getDeclaredMethod("calculateOverallStatus", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(service, steps);
    }

    private GoldenPathOrchestrationService service(
            EvidenceRepository evidenceRepository,
            AiInputBundleRefreshService aiInputBundleRefreshService
    ) {
        return new GoldenPathOrchestrationService(
                mock(ReplayCaseService.class),
                mock(ReplayCaseRepository.class),
                evidenceRepository,
                mock(JiraEvidenceCollectionService.class),
                mock(RepositoryResolutionEvidenceService.class),
                mock(JenkinsEvidenceCollectorService.class),
                mock(ReplayOrchestrator.class),
                aiInputBundleRefreshService,
                mock(DeterministicRootCauseRefreshService.class),
                new ReplayLabProperties(),
                mock(EvidenceService.class),
                new ObjectMapper()
        );
    }
}

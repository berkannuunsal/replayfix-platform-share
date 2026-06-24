package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotJiraPreviewResponse;
import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotResponse;
import com.etiya.replayfix.service.GoldenPathEvidenceSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoldenPathEvidenceSnapshotControllerTest {

    @Test
    void returnsEvidenceSnapshot() throws Exception {
        UUID caseId = UUID.randomUUID();
        GoldenPathEvidenceSnapshotService service =
                mock(GoldenPathEvidenceSnapshotService.class);
        when(service.snapshot(
                eq(caseId),
                eq(false),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(snapshot(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GoldenPathEvidenceSnapshotController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/golden-path/evidence-snapshot",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.snapshotStatus").value("READY"))
                .andExpect(jsonPath("$.sourceContractValidation.valid").value(true))
                .andExpect(jsonPath("$.rovoRcaInputBlock")
                        .value(org.hamcrest.Matchers.containsString(
                                "REPLAYFIX_ROVO_RCA_V1"
                        )));
    }

    @Test
    void returnsJiraPreviewOnly() throws Exception {
        UUID caseId = UUID.randomUUID();
        GoldenPathEvidenceSnapshotService service =
                mock(GoldenPathEvidenceSnapshotService.class);
        when(service.jiraPreview(caseId)).thenReturn(
                new GoldenPathEvidenceSnapshotJiraPreviewResponse(
                        caseId,
                        "FIZZMS-8346",
                        "ReplayFix Evidence Snapshot",
                        true,
                        List.of(),
                        List.of()
                )
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GoldenPathEvidenceSnapshotController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/golden-path/evidence-snapshot/jira-preview",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeToPost").value(true))
                .andExpect(jsonPath("$.preview").value("ReplayFix Evidence Snapshot"));
    }

    private GoldenPathEvidenceSnapshotResponse snapshot(UUID caseId) {
        return new GoldenPathEvidenceSnapshotResponse(
                caseId,
                "FIZZMS-8346",
                "backend",
                "READY",
                false,
                new GoldenPathEvidenceSnapshotResponse.Repository(
                        "DCE",
                        "backend",
                        "test2",
                        "330d124",
                        "RESOLVED"
                ),
                new GoldenPathEvidenceSnapshotResponse.Jenkins(
                        "MODERNIZATION.BACKEND_BUILD_12",
                        "3056",
                        "",
                        "330d124",
                        "SUCCESS"
                ),
                new GoldenPathEvidenceSnapshotResponse.IncidentVersion(
                        "MATCHED",
                        "jenkins-incident-version-validator",
                        "backend-3056",
                        "330d124",
                        true
                ),
                new GoldenPathEvidenceSnapshotResponse.Observability(
                        true,
                        true,
                        true,
                        1,
                        1
                ),
                new GoldenPathEvidenceSnapshotResponse.AiInputBundle(
                        "AVAILABLE",
                        "replayfix-ai-bundle-builder",
                        true,
                        List.of()
                ),
                new GoldenPathEvidenceSnapshotResponse.DeterministicRca(
                        "HYPOTHESIS",
                        "summary",
                        "0.7",
                        List.of("evidence"),
                        List.of()
                ),
                new GoldenPathEvidenceSnapshotResponse.SourceContractValidation(
                        true,
                        List.of(),
                        List.of()
                ),
                "ReplayFix Evidence Snapshot",
                "REPLAYFIX_ROVO_RCA_V1\n{}",
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}

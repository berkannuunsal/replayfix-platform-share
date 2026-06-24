package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.JiraFinalUpdateCommentSection;
import com.etiya.replayfix.api.dto.JiraFinalUpdatePreviewResponse;
import com.etiya.replayfix.service.JiraFinalUpdatePreviewService;
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

class JiraFinalUpdatePreviewControllerTest {

    @Test
    void returnsPreviewForCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        JiraFinalUpdatePreviewService service =
                mock(JiraFinalUpdatePreviewService.class);
        when(service.preview(
                eq(caseId),
                eq(true),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new JiraFinalUpdatePreviewController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/jira-final-update-preview",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.jiraKey").value("FIZZMS-10228"))
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.previewOnly").value(true))
                .andExpect(jsonPath("$.shouldPublish").value(false))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.commentSections[0].title").value("Summary"))
                .andExpect(jsonPath("$.missingEvidence[0]")
                        .value("REPLAY_REPRODUCTION"));
    }

    @Test
    void passesQueryParametersToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        JiraFinalUpdatePreviewService service =
                mock(JiraFinalUpdatePreviewService.class);
        when(service.preview(
                eq(caseId),
                eq(false),
                eq(false),
                eq(false),
                eq(false)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new JiraFinalUpdatePreviewController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/jira-final-update-preview",
                        caseId
                )
                        .param("includeRca", "false")
                        .param("includeTestPlan", "false")
                        .param("includePatchPlan", "false")
                        .param("includeReplayStatus", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewOnly").value(true));
    }

    private JiraFinalUpdatePreviewResponse response(UUID caseId) {
        return new JiraFinalUpdatePreviewResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                true,
                false,
                true,
                List.of(
                        new JiraFinalUpdateCommentSection(
                                "Summary",
                                "Preview only.",
                                List.of("No Jira write")
                        ),
                        new JiraFinalUpdateCommentSection(
                                "Missing evidence",
                                "Evidence missing.",
                                List.of("REPLAY_REPRODUCTION")
                        ),
                        new JiraFinalUpdateCommentSection(
                                "Next action",
                                "Human review required.",
                                List.of("Approve separately")
                        )
                ),
                List.of("REPLAY_REPRODUCTION"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}

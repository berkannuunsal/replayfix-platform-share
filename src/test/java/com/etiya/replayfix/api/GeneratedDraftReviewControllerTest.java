package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replayfix.api.dto.GeneratedDraftReviewedFile;
import com.etiya.replayfix.service.GeneratedDraftReviewService;
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

class GeneratedDraftReviewControllerTest {

    @Test
    void returnsGeneratedDraftReviewForCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        GeneratedDraftReviewService service =
                mock(GeneratedDraftReviewService.class);
        when(service.review(eq(caseId), eq(true), eq(1200)))
                .thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GeneratedDraftReviewController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/generated-draft-review",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.reviewStatus")
                        .value("READY_FOR_HUMAN_REVIEW"))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.shouldRunTests").value(false))
                .andExpect(jsonPath("$.shouldCreateBranch").value(false))
                .andExpect(jsonPath("$.shouldOpenPr").value(false))
                .andExpect(jsonPath("$.reviewedFiles[0].fileType")
                        .value("REGRESSION_TEST"))
                .andExpect(jsonPath("$.reviewedFiles[1].fileType")
                        .value("SOURCE_FIX"));
    }

    @Test
    void passesPreviewOptionsToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        GeneratedDraftReviewService service =
                mock(GeneratedDraftReviewService.class);
        when(service.review(eq(caseId), eq(false), eq(25)))
                .thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GeneratedDraftReviewController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/generated-draft-review",
                        caseId
                )
                        .param("includeFileContentPreview", "false")
                        .param("maxPreviewChars", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus")
                        .value("READY_FOR_HUMAN_REVIEW"));
    }

    private GeneratedDraftReviewResponse response(UUID caseId) {
        return new GeneratedDraftReviewResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "READY_FOR_HUMAN_REVIEW",
                "work/" + caseId + "/repositories/backend",
                List.of(
                        file("REGRESSION_TEST"),
                        file("SOURCE_FIX")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of("Human review is required before test execution."),
                true,
                false,
                false,
                false,
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private GeneratedDraftReviewedFile file(String fileType) {
        return new GeneratedDraftReviewedFile(
                fileType,
                "safe/path",
                true,
                true,
                12,
                "safe preview",
                List.of(),
                List.of(),
                List.of()
        );
    }
}

package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.WorkspaceWriteFileResult;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import com.etiya.replayfix.service.WorkspaceWriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceWriteControllerTest {

    @Test
    void previewReturnsHttp200() throws Exception {
        UUID caseId = UUID.randomUUID();
        WorkspaceWriteService service = mock(WorkspaceWriteService.class);
        when(service.preview(eq(caseId), eq(true), eq(true), eq(true)))
                .thenReturn(response(caseId, "PREVIEW_READY", true, false));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceWriteController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/workspace-write/preview",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeStatus").value("PREVIEW_READY"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.filesWritten").isEmpty())
                .andExpect(jsonPath("$.filesPlanned[0].fileType")
                        .value("REGRESSION_TEST"))
                .andExpect(jsonPath("$.filesPlanned[1].fileType")
                        .value("SOURCE_FIX"))
                .andExpect(jsonPath("$.guardrails[0]")
                        .value("WORKSPACE_ONLY_WRITE"));
    }

    @Test
    void applyWithoutApprovalReturnsConflict() throws Exception {
        UUID caseId = UUID.randomUUID();
        WorkspaceWriteService service = mock(WorkspaceWriteService.class);
        when(service.apply(
                eq(caseId),
                any(),
                eq(false),
                eq(true),
                eq(true)
        )).thenReturn(response(
                caseId,
                "BLOCKED_BY_MISSING_APPROVAL",
                false,
                false
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceWriteController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/workspace-write/apply",
                        caseId
                )
                        .param("dryRun", "false"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.writeStatus")
                        .value("BLOCKED_BY_MISSING_APPROVAL"))
                .andExpect(jsonPath("$.filesWritten").isEmpty());
    }

    @Test
    void applyWithApprovalReturnsHttp200() throws Exception {
        UUID caseId = UUID.randomUUID();
        WorkspaceWriteService service = mock(WorkspaceWriteService.class);
        when(service.apply(
                eq(caseId),
                any(),
                eq(false),
                eq(true),
                eq(true)
        )).thenReturn(response(
                caseId,
                "WRITTEN_TO_WORKSPACE",
                false,
                true
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceWriteController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/workspace-write/apply",
                        caseId
                )
                        .param("dryRun", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvedBy": "controller-test",
                                  "approvalNote": "Workspace-only write.",
                                  "acceptedGuardrails": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeStatus")
                        .value("WRITTEN_TO_WORKSPACE"))
                .andExpect(jsonPath("$.approvalPresent").value(true));
    }

    private WorkspaceWriteResponse response(
            UUID caseId,
            String writeStatus,
            boolean dryRun,
            boolean approvalPresent
    ) {
        List<WorkspaceWriteFileResult> files = List.of(
                new WorkspaceWriteFileResult(
                        "REGRESSION_TEST",
                        "src/test/java/com/company/Test.java",
                        "DRAFT",
                        false,
                        12,
                        "safe content",
                        List.of()
                ),
                new WorkspaceWriteFileResult(
                        "SOURCE_FIX",
                        ".replayfix/drafts/FIZZMS-10228/UserServiceImpl_updateUser_FIX_PLAN.md",
                        "DRAFT",
                        false,
                        12,
                        "safe content",
                        List.of()
                )
        );
        return new WorkspaceWriteResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                writeStatus,
                dryRun,
                "WRITTEN_TO_WORKSPACE".equals(writeStatus) ? files : List.of(),
                files,
                "work/" + caseId + "/repositories/backend",
                true,
                approvalPresent,
                List.of("WORKSPACE_ONLY_WRITE", "HUMAN_APPROVAL_REQUIRED"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}

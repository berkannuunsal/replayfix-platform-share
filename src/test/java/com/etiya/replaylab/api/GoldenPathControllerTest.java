package com.etiya.replaylab.api;

import com.etiya.replaylab.service.GoldenPathOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoldenPathControllerTest {

    @Test
    void missingIncidentVersionCanReturnNeedsEvidenceWithCaseId() throws Exception {
        UUID caseId = UUID.randomUUID();
        GoldenPathOrchestrationService service =
                mock(GoldenPathOrchestrationService.class);
        when(service.executeGoldenPath(
                eq("FIZZMS-10228"),
                eq("bss-monolith"),
                eq(false),
                eq(true)
        )).thenReturn(response(caseId, "NEEDS_EVIDENCE"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GoldenPathController(service))
                .build();

        mockMvc.perform(post("/api/v1/golden-path/execute")
                        .param("jiraKey", "FIZZMS-10228")
                        .param("targetKey", "bss-monolith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_EVIDENCE"))
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.warnings[0]").value(
                        GoldenPathOrchestrationService
                                .INCIDENT_VERSION_EVIDENCE_MISSING
                ));
    }

    @Test
    void includeAiInputBundleFalseIsForwardedToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        GoldenPathOrchestrationService service =
                mock(GoldenPathOrchestrationService.class);
        when(service.executeGoldenPath(
                eq("FIZZMS-10228"),
                eq("bss-monolith"),
                eq(false),
                eq(false)
        )).thenReturn(response(caseId, "PARTIAL"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GoldenPathController(service))
                .build();

        mockMvc.perform(post("/api/v1/golden-path/execute")
                        .param("jiraKey", "FIZZMS-10228")
                        .param("targetKey", "bss-monolith")
                        .param("includeAiInputBundle", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()));

        verify(service).executeGoldenPath(
                "FIZZMS-10228",
                "bss-monolith",
                false,
                false
        );
    }

    private Map<String, Object> response(UUID caseId, String status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("caseId", caseId);
        response.put(
                "warnings",
                List.of(GoldenPathOrchestrationService
                        .INCIDENT_VERSION_EVIDENCE_MISSING)
        );
        return response;
    }
}

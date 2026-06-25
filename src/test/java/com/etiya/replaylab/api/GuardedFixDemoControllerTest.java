package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.GuardedFixDemoPreviewResponse;
import com.etiya.replaylab.api.dto.GuardedFixDemoTestOnlyPrResponse;
import com.etiya.replaylab.service.GuardedFixDemoService;
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

class GuardedFixDemoControllerTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void previewEndpointReturnsAggregatedResponse() throws Exception {
        UUID caseId = UUID.randomUUID();
        GuardedFixDemoService service = mock(GuardedFixDemoService.class);
        when(service.preview(eq(caseId), any()))
                .thenReturn(new GuardedFixDemoPreviewResponse(
                        caseId,
                        "FIZZMS-10228",
                        "READY_FOR_REVIEW",
                        "TEST_ONLY_PR",
                        List.of(),
                        List.of(),
                        List.of(),
                        "Review preview.",
                        Instant.now()
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new GuardedFixDemoController(service))
                .build();

        mvc.perform(post("/api/v1/cases/{caseId}/guarded-fix-demo/preview", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedPath").value("TEST_ONLY_PR"))
                .andExpect(jsonPath("$.demoStatus").value("READY_FOR_REVIEW"));
    }

    @Test
    void testOnlyPrExecuteEndpointReturnsResult() throws Exception {
        UUID caseId = UUID.randomUUID();
        GuardedFixDemoService service = mock(GuardedFixDemoService.class);
        when(service.executeTestOnlyPr(eq(caseId), any()))
                .thenReturn(new GuardedFixDemoTestOnlyPrResponse(
                        caseId,
                        "FIZZMS-10228",
                        true,
                        true,
                        "ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228ReplayLabDemoRegressionTest.java",
                        "bugfix/FIZZMS-10228",
                        "integration/test2/FIZZMS-10228",
                        "abc123",
                        "https://bitbucket/pr/42",
                        "42",
                        List.of(),
                        List.of(),
                        List.of(),
                        Instant.now()
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new GuardedFixDemoController(service))
                .build();

        mvc.perform(post("/api/v1/cases/{caseId}/guarded-fix-demo/test-only-pr/execute", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "requestedBy", "berkan",
                                        "confirmExecute", true,
                                        "guardrailsAccepted", true
                                )
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.testOnly").value(true))
                .andExpect(jsonPath("$.pullRequestUrl")
                        .value("https://bitbucket/pr/42"));
    }
}

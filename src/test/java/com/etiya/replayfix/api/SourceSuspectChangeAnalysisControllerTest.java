package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.service.SourceSuspectChangeAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SourceSuspectChangeAnalysisControllerTest {

    @Test
    void endpointWorksWithCompanyLlmDisabled() {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        SourceSuspectChangeAnalysisController controller =
                new SourceSuspectChangeAnalysisController(service);
        SourceSuspectChangeAnalysisResponse response =
                new SourceSuspectChangeAnalysisResponse(
                        caseId,
                        "FIZZMS-10228",
                        "DCE/backend",
                        "test2",
                        "abc123",
                        45,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new SourceReasoningContext(
                                Map.of(),
                                Map.of(),
                                "",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        false,
                        List.of(),
                        "HYPOTHESIS",
                        0.0,
                        List.of(),
                        "DETERMINISTIC_ONLY",
                        false
                );
        when(service.analyze(caseId, 45, 20, 10, false, false))
                .thenReturn(response);

        SourceSuspectChangeAnalysisResponse actual =
                controller.analyze(caseId, 45, 20, 10, false, false).block();

        assertThat(actual.status()).isEqualTo("HYPOTHESIS");
        assertThat(actual.llmUsed()).isFalse();
    }

    @Test
    void endpointReturnsCompanyLlmUnavailableWarning() {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        SourceSuspectChangeAnalysisController controller =
                new SourceSuspectChangeAnalysisController(service);
        SourceSuspectChangeAnalysisResponse response =
                new SourceSuspectChangeAnalysisResponse(
                        caseId,
                        "FIZZMS-10228",
                        "DCE/backend",
                        "test2",
                        "abc123",
                        45,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new SourceReasoningContext(
                                Map.of(),
                                Map.of(),
                                "",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        false,
                        List.of(),
                        "HYPOTHESIS",
                        0.0,
                        List.of("COMPANY_LLM_UNAVAILABLE"),
                        "DETERMINISTIC_ONLY",
                        true
                );
        when(service.analyze(caseId, 45, 20, 10, false, true))
                .thenReturn(response);

        SourceSuspectChangeAnalysisResponse actual =
                controller.analyze(caseId, 45, 20, 10, false, true).block();

        assertThat(actual.llmUsed()).isFalse();
        assertThat(actual.status()).isEqualTo("HYPOTHESIS");
        assertThat(actual.warnings()).contains("COMPANY_LLM_UNAVAILABLE");
    }

    @Test
    void endpointReturnsHttp200WhenServiceThrows() throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        when(service.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean()
        )).thenThrow(new IllegalStateException("internal stack trace"));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SourceSuspectChangeAnalysisController(service))
                .build();

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/source/suspect-change-analysis",
                        caseId
                ))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.analysisMode")
                        .value("DETERMINISTIC_ONLY"))
                .andExpect(jsonPath("$.warnings[0]")
                        .value(SourceSuspectChangeAnalysisService
                                .SOURCE_CHANGE_ANALYSIS_FAILED))
                .andExpect(content().string(not(containsString(
                        "IllegalStateException"
                ))))
                .andExpect(content().string(not(containsString(
                        "internal stack trace"
                ))));
    }
}

package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.service.SourceSuspectChangeAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void endpointTimeoutIsDynamicForLongCompanyLlmTimeout() {
        assertThat(SourceSuspectChangeAnalysisController.endpointTimeout(
                10,
                8,
                45
        )).isEqualTo(Duration.ofSeconds(78));
    }

    @Test
    void endpointTimeoutKeepsMinimumAndCapsMaximum() {
        assertThat(SourceSuspectChangeAnalysisController.endpointTimeout(
                1,
                1,
                1
        )).isEqualTo(Duration.ofSeconds(30));
        assertThat(SourceSuspectChangeAnalysisController.endpointTimeout(
                90,
                90,
                90
        )).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void endpointWorksWithCompanyLlmDisabled() throws Exception {
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
        when(service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "COMPACT",
                12_000,
                500
        ))
                .thenReturn(response);

        SourceSuspectChangeAnalysisResponse actual =
                await(controller.analyze(
                        caseId,
                        45,
                        20,
                        10,
                        false,
                        false,
                        2_000,
                        256,
                        false,
                        10,
                        8,
                        8,
                        "COMPACT",
                        12_000,
                        500
                ));

        assertThat(actual.status()).isEqualTo("HYPOTHESIS");
        assertThat(actual.llmUsed()).isFalse();
    }

    @Test
    void endpointReturnsCompanyLlmUnavailableWarning() throws Exception {
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
        when(service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "COMPACT",
                12_000,
                500
        ))
                .thenReturn(response);

        SourceSuspectChangeAnalysisResponse actual =
                await(controller.analyze(
                        caseId,
                        45,
                        20,
                        10,
                        false,
                        true,
                        2_000,
                        256,
                        false,
                        10,
                        8,
                        8,
                        "COMPACT",
                        12_000,
                        500
                ));

        assertThat(actual.llmUsed()).isFalse();
        assertThat(actual.status()).isEqualTo("HYPOTHESIS");
        assertThat(actual.warnings()).contains("COMPANY_LLM_UNAVAILABLE");
    }

    @Test
    void controllerUsesServletAsyncTimeoutForLongCompanyLlmRequest()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        when(service.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            Thread.sleep(200);
            return emptyResponse(caseId);
        });

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SourceSuspectChangeAnalysisController(service))
                .build();

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/source/suspect-change-analysis",
                        caseId
                )
                        .param("sourceDiscoveryTimeoutSeconds", "10")
                        .param("gitHistoryTimeoutSeconds", "8")
                        .param("companyLlmTimeoutSeconds", "45")
                        .param("useCompanyLlm", "true")
                        .param("llmContextMode", "MINIMAL")
                        .param("companyLlmMaxOutputTokens", "3000"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getRequest().getAsyncContext().getTimeout())
                .isEqualTo(78_000L);

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"));
    }

    @Test
    void asyncRequestTimeoutExceptionDoesNotReachGlobalHandler()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        when(service.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenThrow(new org.springframework.web.context.request.async
                .AsyncRequestTimeoutException());

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SourceSuspectChangeAnalysisController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/source/suspect-change-analysis",
                        caseId
                )
                        .param("sourceDiscoveryTimeoutSeconds", "10")
                        .param("gitHistoryTimeoutSeconds", "8")
                        .param("companyLlmTimeoutSeconds", "45"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.warnings[0]").value(
                        SourceSuspectChangeAnalysisService
                                .SOURCE_CHANGE_ANALYSIS_TIMEOUT
                ))
                .andExpect(content().string(not(containsString(
                        "ReplayLab operation failed"
                ))))
                .andExpect(content().string(not(containsString(
                        "AsyncRequestTimeoutException"
                ))));
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
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
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

    @Test
    void objectMapperCanSerializeFallbackResponseWhenServiceThrows()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        when(service.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenThrow(new IllegalStateException("internal stack trace"));
        SourceSuspectChangeAnalysisController controller =
                new SourceSuspectChangeAnalysisController(service);

        SourceSuspectChangeAnalysisResponse response =
                await(controller.analyze(
                        caseId,
                        45,
                        20,
                        10,
                        false,
                        false,
                        2_000,
                        256,
                        false,
                        10,
                        8,
                        8,
                        "COMPACT",
                        12_000,
                        500
                ));
        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"status\":\"HYPOTHESIS\"");
        assertThat(json).contains("\"analysisMode\":\"DETERMINISTIC_ONLY\"");
        assertThat(json).contains("\"partial\":true");
        assertThat(json).doesNotContain("IllegalStateException");
        assertThat(json).doesNotContain("internal stack trace");
    }

    @Test
    void controllerReturnsHttp200WhenResponseContainsEmptyLists()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        when(service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "COMPACT",
                12_000,
                500
        ))
                .thenReturn(emptyResponse(caseId));

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
                .andExpect(jsonPath("$.analysisMode")
                        .value("DETERMINISTIC_ONLY"))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.phaseTimingsMs").exists())
                .andExpect(jsonPath("$.lastCompletedPhase")
                        .value("contextBuild"))
                .andExpect(jsonPath("$.flowAnchors").isArray())
                .andExpect(jsonPath("$.candidateFlowChain").isArray())
                .andExpect(jsonPath("$.suspectChanges").isArray());
    }

    @Test
    void controllerResponseIncludesCompanyLlmParseDiagnostics()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
        SourceSuspectChangeAnalysisResponse response =
                responseWithLlmParseDiagnostics(caseId);
        when(service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "MINIMAL",
                12_000,
                500
        ))
                .thenReturn(response);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SourceSuspectChangeAnalysisController(service))
                .build();

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/source/suspect-change-analysis",
                        caseId
                )
                        .param("useCompanyLlm", "true")
                        .param("llmContextMode", "MINIMAL")
                        .param("companyLlmMaxOutputTokens", "500"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyLlmStatus").value("ERROR"))
                .andExpect(jsonPath("$.companyLlmParseErrorCategory")
                        .value("NON_JSON_RESPONSE"))
                .andExpect(jsonPath("$.companyLlmOutputPreview")
                        .value("not json"))
                .andExpect(jsonPath("$.companyLlmEffectiveOutputTokenLimit")
                        .value(500))
                .andExpect(jsonPath("$.companyLlmResponseShape.hasContent")
                        .value(true))
                .andExpect(jsonPath("$.companyLlmResponseShape.extractionSource")
                        .value("content"))
                .andExpect(jsonPath("$.candidateFlowChain[0].className")
                        .value("UserController"));
    }

    @Test
    void controllerReturnsHttp200WhenReasoningContextContainsRiskyValue()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        SourceSuspectChangeAnalysisService service =
                mock(SourceSuspectChangeAnalysisService.class);
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
                                Map.of("workspace", Path.of("work", "case")),
                                Map.of("pattern", java.util.regex.Pattern
                                        .compile("FIZZMS-\\d+")),
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
        when(service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false,
                2_000,
                256,
                false,
                10,
                8,
                8,
                "COMPACT",
                12_000,
                500
        ))
                .thenReturn(response);

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
                .andExpect(jsonPath("$.sourceReasoningContext.caseInfo.workspace")
                        .value(containsString("work")))
                .andExpect(jsonPath("$.sourceReasoningContext.jira.pattern")
                        .value("FIZZMS-\\d+"))
                .andExpect(jsonPath("$.analysisMode")
                        .value("DETERMINISTIC_ONLY"))
                .andExpect(jsonPath("$.partial").value(false));
    }

    private SourceSuspectChangeAnalysisResponse emptyResponse(UUID caseId) {
        return new SourceSuspectChangeAnalysisResponse(
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
                false,
                phaseTimings(),
                "contextBuild",
                null
        );
    }

    private SourceSuspectChangeAnalysisResponse responseWithLlmParseDiagnostics(
            UUID caseId
    ) {
        return new SourceSuspectChangeAnalysisResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "test2",
                "abc123",
                45,
                List.of(),
                List.of(new SourceCandidateFlowChainItem(
                        "CONTROLLER",
                        "ControllerBackend/src/main/java/UserController.java",
                        "UserController",
                        "updateUserParty",
                        List.of("/user/region/update"),
                        "Controller endpoint mapping matched.",
                        "HYPOTHESIS"
                )),
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
                List.of("COMPANY_LLM_INVALID_RESPONSE"),
                "DETERMINISTIC_ONLY",
                true,
                phaseTimings(),
                "companyLlm",
                null,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                8,
                42L,
                "ERROR",
                1000,
                "MINIMAL",
                12000,
                500,
                "prompt-hash",
                "NON_JSON_RESPONSE",
                "not json",
                500,
                Map.of(
                        "hasContent", true,
                        "extractionSource", "content",
                        "parseErrorCategory", "NON_JSON_RESPONSE"
                )
        );
    }

    private Map<String, Long> phaseTimings() {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("evidenceResolution", 1L);
        timings.put("flowAnchorExtraction", 1L);
        timings.put("workspaceResolution", 1L);
        timings.put("sourceDiscovery", 1L);
        timings.put("gitHistory", 1L);
        timings.put("contextBuild", 1L);
        timings.put("companyLlm", 0L);
        timings.put("total", 6L);
        return timings;
    }

    private SourceSuspectChangeAnalysisResponse await(
            DeferredResult<SourceSuspectChangeAnalysisResponse> result
    ) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (result.hasResult()) {
                return (SourceSuspectChangeAnalysisResponse) result.getResult();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("DeferredResult did not complete");
    }
}

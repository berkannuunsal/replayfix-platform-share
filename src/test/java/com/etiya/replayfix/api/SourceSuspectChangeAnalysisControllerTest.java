package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.service.SourceSuspectChangeAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                        List.of()
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
                        List.of("COMPANY_LLM_UNAVAILABLE")
                );
        when(service.analyze(caseId, 45, 20, 10, false, true))
                .thenReturn(response);

        SourceSuspectChangeAnalysisResponse actual =
                controller.analyze(caseId, 45, 20, 10, false, true).block();

        assertThat(actual.llmUsed()).isFalse();
        assertThat(actual.status()).isEqualTo("HYPOTHESIS");
        assertThat(actual.warnings()).contains("COMPANY_LLM_UNAVAILABLE");
    }
}

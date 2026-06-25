package com.etiya.replaylab.api;

import com.etiya.replaylab.model.ApplicationDbEvidenceResponse;
import com.etiya.replaylab.service.ApplicationDbEvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationDbEvidenceControllerTest {

    @Test
    void endpointReturnsHttp200WithExecuteFalseAndNoDatasource()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        ApplicationDbEvidenceService service =
                mock(ApplicationDbEvidenceService.class);
        when(service.collect(eq(caseId), anyString(), anyBoolean(), anyInt()))
                .thenReturn(new ApplicationDbEvidenceResponse(
                        caseId,
                        "FIZZMS-10228",
                        "HYPOTHESIS",
                        "backend",
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        List.of(ApplicationDbEvidenceService
                                .APPLICATION_DB_DATASOURCE_NOT_CONFIGURED)
                ));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApplicationDbEvidenceController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/db-evidence",
                        caseId
                ).param("execute", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.dataSourceKey").value("backend"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.masked").value(true))
                .andExpect(jsonPath("$.warnings[0]")
                        .value(ApplicationDbEvidenceService
                                .APPLICATION_DB_DATASOURCE_NOT_CONFIGURED));
    }
}

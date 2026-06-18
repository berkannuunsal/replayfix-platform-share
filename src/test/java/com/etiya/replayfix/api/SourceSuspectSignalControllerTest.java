package com.etiya.replayfix.api;

import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.service.SuspectSignalExtractionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSuspectSignalControllerTest {

    @Test
    void shouldReturnSuspectSignalsForCase() {
        UUID caseId = UUID.randomUUID();
        SuspectSignalExtractionService service =
                mock(SuspectSignalExtractionService.class);
        SourceSuspectSignalController controller =
                new SourceSuspectSignalController(service);

        SuspectSignalExtractionResponse response =
                new SuspectSignalExtractionResponse(
                        caseId,
                        "FIZZMS-10228",
                        "DCE/backend",
                        "test2",
                        List.of(new SuspectSourceSignal(
                                "tax_info",
                                SuspectSignalCategory.BUSINESS_TERM,
                                List.of("ROVO_RCA"),
                                "Signal from Rovo RCA normalized JSON"
                        )),
                        List.of()
                );

        when(service.extract(caseId)).thenReturn(response);

        SuspectSignalExtractionResponse actual =
                controller.suspectSignals(caseId).block();

        assertEquals(caseId, actual.caseId());
        assertEquals("FIZZMS-10228", actual.jiraKey());
        assertEquals("DCE/backend", actual.repository());
        assertEquals("test2", actual.branch());
        assertEquals("tax_info", actual.signals().get(0).value());
        assertEquals(
                SuspectSignalCategory.BUSINESS_TERM,
                actual.signals().get(0).category()
        );
    }
}

package com.etiya.replaylab.api;

import com.etiya.replaylab.model.SuspectSignalCategory;
import com.etiya.replaylab.model.SuspectSignalExtractionResponse;
import com.etiya.replaylab.model.SuspectSignalStrength;
import com.etiya.replaylab.model.SuspectSourceSignal;
import com.etiya.replaylab.service.SuspectSignalExtractionService;
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
                                SuspectSignalStrength.STRONG,
                                List.of("ROVO_RCA"),
                                "Signal from Rovo RCA normalized JSON"
                        )),
                        0,
                        List.of()
                );

        when(service.extract(caseId, false)).thenReturn(response);

        SuspectSignalExtractionResponse actual =
                controller.suspectSignals(caseId, false).block();

        assertEquals(caseId, actual.caseId());
        assertEquals("FIZZMS-10228", actual.jiraKey());
        assertEquals("DCE/backend", actual.repository());
        assertEquals("test2", actual.branch());
        assertEquals("tax_info", actual.signals().get(0).value());
        assertEquals(
                SuspectSignalCategory.BUSINESS_TERM,
                actual.signals().get(0).category()
        );
        assertEquals(
                SuspectSignalStrength.STRONG,
                actual.signals().get(0).strength()
        );
    }

    @Test
    void shouldReturnWeakSignalsWhenRequested() {
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
                                "account creation",
                                SuspectSignalCategory.BUSINESS_TERM,
                                SuspectSignalStrength.WEAK,
                                List.of("ROVO_RCA"),
                                "Signal from Rovo RCA human report"
                        )),
                        0,
                        List.of()
                );

        when(service.extract(caseId, true)).thenReturn(response);

        SuspectSignalExtractionResponse actual =
                controller.suspectSignals(caseId, true).block();

        assertEquals("account creation", actual.signals().get(0).value());
        assertEquals(
                SuspectSignalStrength.WEAK,
                actual.signals().get(0).strength()
        );
    }
}

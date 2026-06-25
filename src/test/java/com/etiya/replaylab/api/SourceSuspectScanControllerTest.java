package com.etiya.replaylab.api;

import com.etiya.replaylab.model.SourceSuspectScanResponse;
import com.etiya.replaylab.service.SourceSuspectScanService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSuspectScanControllerTest {

    @Test
    void shouldReturnSourceSuspectScanResponse() {
        UUID caseId = UUID.randomUUID();
        SourceSuspectScanService service =
                mock(SourceSuspectScanService.class);
        SourceSuspectScanController controller =
                new SourceSuspectScanController(service);

        SourceSuspectScanResponse response =
                new SourceSuspectScanResponse(
                        caseId,
                        "FIZZMS-10228",
                        "DCE/backend",
                        "test2",
                        3,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        "work/case/repository",
                        true,
                        1,
                        3,
                        0,
                        Map.of("java", 1),
                        3,
                        List.of("PREFERRED_PROVINCE"),
                        "backend",
                        "Backend Service"
                );

        when(service.scan(caseId, 20, 5, false)).thenReturn(response);

        SourceSuspectScanResponse actual =
                controller.suspectScan(caseId, 20, 5, false).block();

        assertEquals(caseId, actual.caseId());
        assertEquals("FIZZMS-10228", actual.jiraKey());
        assertEquals("DCE/backend", actual.repository());
        assertEquals("test2", actual.branch());
        assertEquals(3, actual.signalCount());
        assertEquals(0, actual.candidateFileCount());
        assertEquals("work/case/repository", actual.scannedRoot());
        assertEquals(1, actual.scannedFileCount());
    }
}

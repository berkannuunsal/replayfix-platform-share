package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties.Target;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.IncidentSignals;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SourceCodeContextServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsRelevantSourceFile() throws Exception {
        Path sourceFile = temporaryDirectory.resolve(
                "src/main/java/com/example/"
                        + "CustomerOrderIntegrationClient.java"
        );

        Files.createDirectories(
                sourceFile.getParent()
        );

        Files.writeString(
                sourceFile,
                """
                        package com.example;
                        
                        public class CustomerOrderIntegrationClient {
                            public void completeCustomerOrder() {
                                String endpoint = "/customerorder/omintegration/complete";
                                // HTTP 401 must be handled as recoverable.
                            }
                        }
                        """
        );

        Target target = new Target();
        target.setRepository("bss-backend");
        target.setLocalSourcePath(
                temporaryDirectory.toString()
        );

        SourceCodeContextService service =
                new SourceCodeContextService(
                        mock(GitWorkspaceService.class),
                        new EvidenceSanitizer()
                );

        JiraIssue issue = new JiraIssue(
                "FIZZMS-8346",
                "BAR customer order completion returns 401",
                "",
                Map.of()
        );

        IncidentSignals signals = new IncidentSignals(
                List.of(
                        "/customerorder/omintegration/complete"
                ),
                List.of("401"),
                List.of("9999"),
                List.of(
                        "BAR",
                        "customerorder"
                ),
                List.of("12"),
                List.of("bss-backend")
        );

        IncidentTimeline timeline = new IncidentTimeline(
                null,
                null,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );

        var result = service.collect(
                new ReplayCaseEntity(),
                target,
                issue,
                "",
                signals,
                timeline
        );

        assertThat(result.sourceMode())
                .isEqualTo("LOCAL");

        assertThat(result.scannedFileCount())
                .isGreaterThan(0);

        assertThat(result.excerpts())
                .isNotEmpty();

        assertThat(result.excerpts().get(0).path())
                .contains(
                        "CustomerOrderIntegrationClient.java"
                );

        assertThat(result.excerpts().get(0).content())
                .contains(
                        "/customerorder/omintegration/complete"
                );
    }
}

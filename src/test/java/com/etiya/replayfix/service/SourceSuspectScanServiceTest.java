package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.SourceSuspectScanResponse;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSuspectScanServiceTest {

    @TempDir
    Path temporaryDirectory;

    private SuspectSignalExtractionService signalExtractionService;
    private EvidenceRepository evidenceRepository;
    private ReplayFixProperties properties;
    private SourceSuspectScanService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        signalExtractionService = mock(SuspectSignalExtractionService.class);
        evidenceRepository = mock(EvidenceRepository.class);
        properties = new ReplayFixProperties();
        properties.setWorkspaceDir(
                temporaryDirectory.resolve("work").toString()
        );
        service = new SourceSuspectScanService(
                signalExtractionService,
                properties,
                evidenceRepository,
                new ObjectMapper().findAndRegisterModules()
        );

        when(evidenceRepository.findByCaseIdAndEvidenceType(
                any(),
                eq(EvidenceType.SOURCE_CHECKOUT)
        )).thenReturn(List.of());
    }

    @Test
    void scansJavaFilesAndFindsPreferredProvince() throws Exception {
        Path sourceFile = sourceRoot()
                .resolve("src/main/java/com/example/RegionController.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                """
                        package com.example;

                        public class RegionController {
                            @PostMapping("/user/region/update")
                            public void updateRegion() {
                                String field = "PREFERRED_PROVINCE";
                            }
                        }
                        """
        );
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFileCount()).isEqualTo(1);
        assertThat(response.candidateFiles().get(0).relativePath())
                .isEqualTo("src/main/java/com/example/RegionController.java");
        assertThat(response.candidateFiles().get(0).matchedSignals())
                .containsExactly("PREFERRED_PROVINCE");
        assertThat(response.candidateFiles().get(0).snippets().get(0).text())
                .contains("PREFERRED_PROVINCE");
    }

    @Test
    void extractsClassNameAndMethodNameForJavaMatch() throws Exception {
        Path sourceFile = sourceRoot()
                .resolve("src/main/java/com/example/RegionController.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                """
                        package com.example;

                        public class RegionController {
                            @PostMapping("/user/region/update")
                            public void updateRegion() {
                                String field = "PREFERRED_PROVINCE";
                            }
                        }
                        """
        );
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFiles().get(0).className())
                .isEqualTo("RegionController");
        assertThat(response.candidateFiles().get(0).candidateMethods())
                .hasSize(1);
        assertThat(response.candidateFiles().get(0).candidateMethods().get(0)
                .methodName()).isEqualTo("updateRegion");
        assertThat(response.candidateFiles().get(0).candidateMethods().get(0)
                .annotations()).containsExactly(
                "@PostMapping(\"/user/region/update\")"
        );
        assertThat(response.candidateMethodCount()).isEqualTo(1);
    }

    @Test
    void scansYamlPropertiesAndXmlFiles() throws Exception {
        Path root = sourceRoot();
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(
                root.resolve("src/main/resources/application.yml"),
                "taxInfo: enabled\n"
        );
        Files.writeString(
                root.resolve("src/main/resources/application.properties"),
                "billingAccount.region=TR\n"
        );
        Files.writeString(
                root.resolve("src/main/resources/mapper.xml"),
                "<field name=\"TimeZone\" />\n"
        );
        stubSignals("taxInfo", "billingAccount", "TimeZone");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFiles())
                .extracting("fileType")
                .contains("yml", "properties", "xml");
        assertThat(response.candidateFileCount()).isEqualTo(3);
    }

    @Test
    void excludesBuildAndVcsDirectories() throws Exception {
        Path root = sourceRoot();
        write(root.resolve("target/Generated.java"));
        write(root.resolve("build/Generated.java"));
        write(root.resolve("node_modules/module/index.js"));
        write(root.resolve(".git/hooks/pre-commit.js"));
        write(root.resolve("src/main/java/com/example/RegionService.java"));
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFileCount()).isEqualTo(1);
        assertThat(response.candidateFiles().get(0).relativePath())
                .isEqualTo("src/main/java/com/example/RegionService.java");
    }

    @Test
    void limitsMaxFilesAndSnippets() throws Exception {
        Path root = sourceRoot();
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(
                root.resolve("src/main/java/com/example/A.java"),
                """
                        class A {
                            void one() { String a = "region"; }
                            void two() { String b = "province"; }
                        }
                        """
        );
        Files.writeString(
                root.resolve("src/main/java/com/example/B.java"),
                """
                        class B {
                            void one() { String a = "region"; }
                        }
                        """
        );
        stubSignals("region", "province");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                1,
                1,
                false
        );

        assertThat(response.candidateFileCount()).isEqualTo(1);
        assertThat(response.candidateFiles().get(0).snippets()).hasSize(1);
    }

    @Test
    void returnsWorkspaceMissingWarningWhenWorkspaceCannotBeFound() {
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFileCount()).isZero();
        assertThat(response.candidateFiles()).isEmpty();
        assertThat(response.warnings())
                .contains(SourceSuspectScanService.SOURCE_WORKSPACE_NOT_FOUND);
    }

    private Path sourceRoot() throws Exception {
        Path root = Path.of(
                properties.getWorkspaceDir(),
                caseId.toString(),
                "repository"
        );
        Files.createDirectories(root);
        return root;
    }

    private void write(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "PREFERRED_PROVINCE\n");
    }

    private void stubSignals(String... values) {
        when(signalExtractionService.extract(caseId, false))
                .thenReturn(response(values));
    }

    private SuspectSignalExtractionResponse response(String... values) {
        return new SuspectSignalExtractionResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "test2",
                List.of(values)
                        .stream()
                        .map(value -> new SuspectSourceSignal(
                                value,
                                SuspectSignalCategory.BUSINESS_TERM,
                                SuspectSignalStrength.STRONG,
                                List.of("ROVO_RCA"),
                                "test"
                        ))
                        .toList(),
                0,
                List.of()
        );
    }
}

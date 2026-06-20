package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
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
import java.time.Instant;
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
                eq(EvidenceType.SOURCE_CONTEXT)
        )).thenReturn(List.of());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                any(),
                eq(EvidenceType.REPOSITORY_RESOLUTION)
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
        assertThat(response.scannedRoot()).isNotBlank();
        assertThat(response.workspaceResolved()).isTrue();
        assertThat(response.scannedFileCount()).isEqualTo(1);
        assertThat(response.fileExtensionCounts()).containsEntry("java", 1);
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
        assertThat(response.fileExtensionCounts())
                .containsEntry("yml", 1)
                .containsEntry("properties", 1)
                .containsEntry("xml", 1);
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
        assertThat(response.skippedDirectoryCount()).isGreaterThanOrEqualTo(4);
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
        assertThat(response.workspaceResolved()).isFalse();
        assertThat(response.scannedRoot()).isEmpty();
        assertThat(response.warnings())
                .contains(SourceSuspectScanService.SOURCE_WORKSPACE_NOT_FOUND);
    }

    @Test
    void resolvesBranchFromRepositoryResolutionSourceBranch()
            throws Exception {
        Path sourceFile = sourceRoot()
                .resolve("src/main/java/com/example/RegionService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class RegionService { String p = \"PREFERRED_PROVINCE\"; }\n");
        stubSignalsWithBranch("", "PREFERRED_PROVINCE");
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.REPOSITORY_RESOLUTION
        )).thenReturn(List.of(evidence(
                EvidenceType.REPOSITORY_RESOLUTION,
                """
                        {
                          "projectKey": "DCE",
                          "primaryRepositorySlug": "backend",
                          "repositoryName": "Backend Service",
                          "sourceBranch": "test2"
                        }
                        """
        )));

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.branch()).isEqualTo("test2");
        assertThat(response.repositorySlug()).isEqualTo("backend");
        assertThat(response.repositoryName()).isEqualTo("Backend Service");
        assertThat(response.warnings())
                .doesNotContain(SourceSuspectScanService.SOURCE_BRANCH_NOT_RESOLVED);
    }

    @Test
    void returnsNoSupportedFilesWarningWhenWorkspaceHasNoSupportedFiles()
            throws Exception {
        Path root = sourceRoot();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/readme.txt"), "PREFERRED_PROVINCE\n");
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.workspaceResolved()).isTrue();
        assertThat(response.scannedFileCount()).isZero();
        assertThat(response.warnings())
                .contains(SourceSuspectScanService
                        .SOURCE_WORKSPACE_EMPTY_OR_NO_SUPPORTED_FILES);
    }

    @Test
    void returnsNoMatchesWarningWhenSupportedFilesDoNotMatch()
            throws Exception {
        Path sourceFile = sourceRoot()
                .resolve("src/main/java/com/example/RegionService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class RegionService {}\n");
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.scannedFileCount()).isEqualTo(1);
        assertThat(response.candidateFileCount()).isZero();
        assertThat(response.warnings())
                .contains(SourceSuspectScanService.NO_SOURCE_SIGNAL_MATCHES_FOUND);
    }

    @Test
    void findsBusinessTermCaseInsensitively() throws Exception {
        Path sourceFile = sourceRoot()
                .resolve("src/main/java/com/example/BillingService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                "class BillingService { String field = \"BillingAccount\"; }\n"
        );
        stubSignals("billingAccount");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.candidateFileCount()).isEqualTo(1);
        assertThat(response.candidateFiles().get(0).matchedSignals())
                .containsExactly("billingAccount");
    }

    @Test
    void diagnosticsDoNotExposeSecretPathSegments() throws Exception {
        properties.setWorkspaceDir(
                temporaryDirectory.resolve("token-secret-work").toString()
        );
        Path root = Path.of(
                properties.getWorkspaceDir(),
                caseId.toString(),
                "repository"
        );
        Files.createDirectories(root);
        Files.writeString(root.resolve("service-token-value.java"),
                "PREFERRED_PROVINCE\n");
        stubSignals("PREFERRED_PROVINCE");

        SourceSuspectScanResponse response = service.scan(
                caseId,
                20,
                5,
                false
        );

        assertThat(response.scannedRoot()).doesNotContain("token");
        assertThat(response.usedSignalsPreview())
                .containsExactly("PREFERRED_PROVINCE");
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
        stubSignalsWithBranch("test2", values);
    }

    private void stubSignalsWithBranch(String branch, String... values) {
        when(signalExtractionService.extract(caseId, false))
                .thenReturn(response(branch, values));
    }

    private SuspectSignalExtractionResponse response(
            String branch,
            String... values
    ) {
        return new SuspectSignalExtractionResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                branch,
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

    private EvidenceEntity evidence(
            EvidenceType type,
            String content
    ) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(type);
        evidence.setSource("test");
        evidence.setContentText(content);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

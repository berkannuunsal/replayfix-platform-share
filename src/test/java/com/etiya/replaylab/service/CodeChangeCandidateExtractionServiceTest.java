package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryCandidateHint;
import com.etiya.replaylab.api.dto.CodeChangeCandidateExtractionResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.BitbucketSourceReadClient;
import com.etiya.replaylab.integration.BitbucketSourceReadClient.SourceFileFetchResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeChangeCandidateExtractionServiceTest {

    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayLabProperties properties;
    private BitbucketSourceReadClient bitbucketSourceReadClient;
    private CodeChangeCandidateExtractionService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketSourceReadClient = mock(BitbucketSourceReadClient.class);
        properties = new ReplayLabProperties();
        service = new CodeChangeCandidateExtractionService(
                caseRepository,
                evidenceRepository,
                properties,
                new ObjectMapper().findAndRegisterModules(),
                bitbucketSourceReadClient
        );
        caseId = UUID.randomUUID();
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void extractsJavaMethodSnippetFromWorkspace(@TempDir Path root)
            throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("OrderService.java"), """
                class OrderService {
                    public void complete(Order order) {
                        order.setStatus("COMPLETE");
                    }
                }
                """);
        sourceEvidence("OrderService.java", "OrderService", "complete", "JAVA");

        List<CodeChangeAdvisoryCandidateHint> hints =
                service.extractCandidateHints(caseId, 3, 12000);

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).codeSnippet())
                .contains("public void complete")
                .contains("setStatus");
    }

    @Test
    void extractsTypeScriptFunctionSnippetFromWorkspace(@TempDir Path root)
            throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("CustomerPanel.tsx"), """
                export function CustomerPanel() {
                    return <section>Customer</section>;
                }
                """);
        sourceEvidence(
                "CustomerPanel.tsx",
                "CustomerPanel",
                "CustomerPanel",
                "TYPESCRIPT"
        );

        List<CodeChangeAdvisoryCandidateHint> hints =
                service.extractCandidateHints(caseId, 3, 12000);

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).language()).isEqualTo("TYPESCRIPT");
        assertThat(hints.get(0).codeSnippet())
                .contains("export function CustomerPanel");
    }

    @Test
    void rejectsPathTraversal(@TempDir Path root) {
        configureTarget(root);
        sourceEvidence("../OrderService.java", "OrderService", "complete", "JAVA");

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_PATH_TRAVERSAL_REJECTED");
    }

    @Test
    void rejectsFileOutsideWorkspace(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        configureTarget(root);
        Path outsideFile = outside.resolve("OrderService.java");
        Files.writeString(outsideFile, "class OrderService {}\n");
        sourceEvidence(
                outsideFile.toString(),
                "OrderService",
                "complete",
                "JAVA"
        );

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_FILE_OUTSIDE_WORKSPACE");
    }

    @Test
    void rejectsUnsupportedExtension(@TempDir Path root) throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("notes.txt"), "not code\n");
        sourceEvidence("notes.txt", "Notes", "run", "UNKNOWN");

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_FILE_EXTENSION_UNSUPPORTED:notes.txt");
    }

    @Test
    void rejectsSnippetWithSensitiveMarker(@TempDir Path root)
            throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("OrderService.java"), """
                class OrderService {
                    public void complete() {
                        String header = "Authorization: Bearer abc";
                    }
                }
                """);
        sourceEvidence("OrderService.java", "OrderService", "complete", "JAVA");

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, true);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_SNIPPET_SENSITIVE_MARKER_REJECTED:Authorization");
        assertThat(response.toString()).doesNotContain("Bearer abc");
    }

    @Test
    void maxCandidatesIsRespected(@TempDir Path root) throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("A.java"), "class A { void a() {} }\n");
        Files.writeString(root.resolve("B.java"), "class B { void b() {} }\n");
        evidence("""
                {
                  "suspectChanges": [
                    {"filePath":"A.java","className":"A","methodName":"a","language":"JAVA"},
                    {"filePath":"B.java","className":"B","methodName":"b","language":"JAVA"}
                  ]
                }
                """);

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 1, 12000, false);

        assertThat(response.candidateCount()).isEqualTo(1);
    }

    @Test
    void maxSnippetCharsIsRespected(@TempDir Path root) throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("OrderService.java"), """
                class OrderService {
                    public void complete() {
                        System.out.println("a long line");
                    }
                }
                """);
        sourceEvidence("OrderService.java", "OrderService", "complete", "JAVA");

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 10, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_SNIPPET_TOO_LARGE:OrderService.java");
    }

    @Test
    void extractsJavaMethodSnippetFromBitbucketRawFile() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/main/java/com/acme/OrderService.java",
                "OrderService",
                "complete",
                "JAVA"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/OrderService.java",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        class OrderService {
                            public void complete(Order order) {
                                order.setStatus("COMPLETE");
                            }
                        }
                        """,
                "backend",
                "DCE",
                "backend",
                "test2",
                "src/main/java/com/acme/OrderService.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.sourceCandidateSource()).isEqualTo("BITBUCKET");
        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.candidates().get(0).repositoryLogicalName())
                .isEqualTo("backend");
        assertThat(response.candidates().get(0).branch()).isEqualTo("test2");
        List<CodeChangeAdvisoryCandidateHint> hints =
                service.extractCandidateHints(caseId, 3, 12000);
        assertThat(hints.get(0).codeSnippet()).contains("setStatus");
        assertThat(hints.get(0).constraints())
                .contains("sourceCandidateSource=BITBUCKET",
                        "repositoryLogicalName=backend",
                        "branch=test2");
    }

    @Test
    void extractsTypeScriptFunctionSnippetFromBitbucketRawFile() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/app/CustomerPanel.tsx",
                "CustomerPanel",
                "CustomerPanel",
                "TYPESCRIPT"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("customerUi");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/app/CustomerPanel.tsx",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        export function CustomerPanel() {
                            return <section>Customer</section>;
                        }
                        """,
                "customer-ui",
                "DCE",
                "customer-ui",
                "test2",
                "src/app/CustomerPanel.tsx",
                List.of()
        ));

        List<CodeChangeAdvisoryCandidateHint> hints =
                service.extractCandidateHints(caseId, 3, 12000);

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).language()).isEqualTo("TYPESCRIPT");
        assertThat(hints.get(0).codeSnippet())
                .contains("export function CustomerPanel");
    }

    @Test
    void bitbucketRejectsPathTraversal() {
        configureBitbucketTarget();
        sourceEvidence(
                "../OrderService.java",
                "OrderService",
                "complete",
                "JAVA"
        );

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_PATH_TRAVERSAL_REJECTED");
        verify(bitbucketSourceReadClient, never()).fetchRawFile(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void bitbucketRejectsUnsupportedExtension() {
        configureBitbucketTarget();
        sourceEvidence("notes.txt", "Notes", "run", "UNKNOWN");

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("SOURCE_FILE_EXTENSION_UNSUPPORTED:notes.txt");
    }

    @Test
    void bitbucketCredentialsMissingCreatesBlocker() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/main/java/com/acme/OrderService.java",
                "OrderService",
                "complete",
                "JAVA"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/OrderService.java",
                "test2"
        )).thenReturn(SourceFileFetchResult.failure(
                BitbucketSourceReadClient.CREDENTIALS_NOT_CONFIGURED,
                repository,
                "test2",
                "src/main/java/com/acme/OrderService.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.blockers())
                .contains("BITBUCKET_CREDENTIALS_NOT_CONFIGURED");
        assertThat(response.toString())
                .doesNotContain("BITBUCKET_USERNAME")
                .doesNotContain("BITBUCKET_TOKEN");
    }

    @Test
    void bitbucketUnauthorizedCreatesBlocker() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/main/java/com/acme/OrderService.java",
                "OrderService",
                "complete",
                "JAVA"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/OrderService.java",
                "test2"
        )).thenReturn(SourceFileFetchResult.failure(
                BitbucketSourceReadClient.READ_NOT_AUTHORIZED,
                repository,
                "test2",
                "src/main/java/com/acme/OrderService.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.blockers())
                .contains("BITBUCKET_READ_NOT_AUTHORIZED");
    }

    @Test
    void bitbucketFileNotFoundCreatesMissingEvidence() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/main/java/com/acme/Missing.java",
                "Missing",
                "run",
                "JAVA"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/Missing.java",
                "test2"
        )).thenReturn(SourceFileFetchResult.failure(
                BitbucketSourceReadClient.FILE_NOT_FOUND,
                repository,
                "test2",
                "src/main/java/com/acme/Missing.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, false);

        assertThat(response.missingEvidence())
                .contains("BITBUCKET_FILE_NOT_FOUND:"
                        + "src/main/java/com/acme/Missing.java");
    }

    @Test
    void bitbucketRejectsSensitiveFetchedContent() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        sourceEvidence(
                "src/main/java/com/acme/OrderService.java",
                "OrderService",
                "complete",
                "JAVA"
        );
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/OrderService.java",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                "class OrderService { String password = \"abc\"; }",
                "backend",
                "DCE",
                "backend",
                "test2",
                "src/main/java/com/acme/OrderService.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response =
                service.extract(caseId, 3, 12000, true);

        assertThat(response.candidateCount()).isZero();
        assertThat(response.warnings())
                .contains("BITBUCKET_SOURCE_SENSITIVE_MARKER_REJECTED:password");
        assertThat(response.toString()).doesNotContain("abc");
    }

    @Test
    void hydratesBackendJavaCandidateAndStripsBssBackendPrefix() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/UserServiceImpl.java",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        class UserServiceImpl {
                            public void updateUser(User user) {
                                user.touch();
                            }
                        }
                        """,
                "backend",
                "DCE",
                "backend",
                "test2",
                "src/main/java/com/acme/UserServiceImpl.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response = service.hydrate(
                caseId,
                List.of(new CodeChangeAdvisoryCandidateHint(
                        "backend",
                        "bss-backend/src/main/java/com/acme/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        "JAVA",
                        "",
                        "",
                        "",
                        List.of()
                )),
                3,
                12000,
                true
        );

        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.candidates().get(0).sourceCandidateSource())
                .isEqualTo("BITBUCKET");
        assertThat(response.candidates().get(0).repositoryLogicalName())
                .isEqualTo("backend");
        assertThat(response.candidates().get(0).normalizedFilePath())
                .isEqualTo("src/main/java/com/acme/UserServiceImpl.java");
        assertThat(response.candidates().get(0).snippetPreview())
                .contains("public void updateUser");
    }

    @Test
    void hydratesBackendJavaCandidateAndStripsBackendPrefix() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("backend");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/main/java/com/acme/UserServiceImpl.java",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        class UserServiceImpl {
                            public void updateUser() {
                                run();
                            }
                        }
                        """,
                "backend",
                "DCE",
                "backend",
                "test2",
                "src/main/java/com/acme/UserServiceImpl.java",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response = service.hydrate(
                caseId,
                List.of(new CodeChangeAdvisoryCandidateHint(
                        "backend",
                        "backend/src/main/java/com/acme/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        "JAVA",
                        "",
                        "",
                        "",
                        List.of()
                )),
                3,
                12000,
                false
        );

        assertThat(response.candidates().get(0).normalizedFilePath())
                .isEqualTo("src/main/java/com/acme/UserServiceImpl.java");
    }

    @Test
    void hydratesCustomerUiTypescriptCandidateAndStripsPrefixes() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("customerUi");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/app/UserPanel.tsx",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        export function updateUser() {
                            return <section>User</section>;
                        }
                        """,
                "customer-ui",
                "DCE",
                "customer-ui",
                "test2",
                "src/app/UserPanel.tsx",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response = service.hydrate(
                caseId,
                List.of(new CodeChangeAdvisoryCandidateHint(
                        "customer-ui",
                        "frontend/customer-ui/src/app/UserPanel.tsx",
                        "UserPanel",
                        "updateUser",
                        "TYPESCRIPT",
                        "",
                        "",
                        "",
                        List.of()
                )),
                3,
                12000,
                true
        );

        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.candidates().get(0).repositoryLogicalName())
                .isEqualTo("customer-ui");
        assertThat(response.candidates().get(0).normalizedFilePath())
                .isEqualTo("src/app/UserPanel.tsx");
        assertThat(response.candidates().get(0).snippetPreview())
                .contains("export function updateUser");
    }

    @Test
    void hydratesCustomerUiTypescriptCandidateAndStripsCustomerUiPrefix() {
        ReplayLabProperties.Target target = configureBitbucketTarget();
        ReplayLabProperties.SourceCandidateRepository repository =
                target.getBitbucket().getRepositories().get("customerUi");
        when(bitbucketSourceReadClient.fetchRawFile(
                target.getBitbucket(),
                repository,
                "src/app/UserPanel.tsx",
                "test2"
        )).thenReturn(new SourceFileFetchResult(
                true,
                "OK",
                """
                        export function updateUser() {
                            return null;
                        }
                        """,
                "customer-ui",
                "DCE",
                "customer-ui",
                "test2",
                "src/app/UserPanel.tsx",
                List.of()
        ));

        CodeChangeCandidateExtractionResponse response = service.hydrate(
                caseId,
                List.of(new CodeChangeAdvisoryCandidateHint(
                        "customer-ui",
                        "customer-ui/src/app/UserPanel.tsx",
                        "UserPanel",
                        "updateUser",
                        "TYPESCRIPT",
                        "",
                        "",
                        "",
                        List.of()
                )),
                3,
                12000,
                false
        );

        assertThat(response.candidates().get(0).normalizedFilePath())
                .isEqualTo("src/app/UserPanel.tsx");
    }

    @Test
    void hydrateReturnsRepositoryMappingUnknownWhenUnclear() {
        configureBitbucketTarget();

        CodeChangeCandidateExtractionResponse response = service.hydrate(
                caseId,
                List.of(new CodeChangeAdvisoryCandidateHint(
                        "unknown-repo",
                        "src/main/java/com/acme/UserServiceImpl.java",
                        "",
                        "",
                        "JAVA",
                        "",
                        "",
                        "",
                        List.of()
                )),
                3,
                12000,
                false
        );

        assertThat(response.candidateCount()).isZero();
        assertThat(response.missingEvidence())
                .contains("REPOSITORY_MAPPING_UNKNOWN:"
                        + "src/main/java/com/acme/UserServiceImpl.java");
    }

    @Test
    void previewIsCappedAndNotReturnedByDefault(@TempDir Path root)
            throws Exception {
        configureTarget(root);
        Files.writeString(root.resolve("OrderService.java"), """
                class OrderService {
                    public void complete() {
                        order();
                    }
                }
                """);
        sourceEvidence("OrderService.java", "OrderService", "complete", "JAVA");

        CodeChangeCandidateExtractionResponse hidden =
                service.extract(caseId, 3, 12000, false);
        CodeChangeCandidateExtractionResponse preview =
                service.extract(caseId, 3, 12000, true);

        assertThat(hidden.candidates().get(0).snippetPreview()).isBlank();
        assertThat(preview.candidates().get(0).snippetPreview())
                .contains("complete")
                .hasSizeLessThanOrEqualTo(500);
    }

    private void configureTarget(Path root) {
        ReplayLabProperties.Target target = new ReplayLabProperties.Target();
        target.setSourceCandidateExtractionEnabled(true);
        target.setSourceWorkspaceRoot(root.toString());
        target.setAllowedSourceExtensions(List.of(".java", ".ts", ".tsx"));
        target.setMaxSourceCandidateFiles(3);
        properties.getTargets().put("bss-monolith", target);
    }

    private ReplayLabProperties.Target configureBitbucketTarget() {
        ReplayLabProperties.Target target = new ReplayLabProperties.Target();
        target.setSourceCandidateExtractionEnabled(true);
        target.setSourceCandidateSource("BITBUCKET");
        target.setSourceCandidateExtractionBranch("test2");
        target.setAllowedSourceExtensions(List.of(".java", ".ts", ".tsx"));
        target.setMaxSourceCandidateFiles(5);
        target.setMaxSnippetChars(12000);

        ReplayLabProperties.SourceCandidateBitbucket bitbucket =
                new ReplayLabProperties.SourceCandidateBitbucket();
        bitbucket.setBaseUrl("https://bitbucket.etiya.com");
        bitbucket.setUsernameEnv("BITBUCKET_USERNAME");
        bitbucket.setTokenEnv("BITBUCKET_TOKEN");

        ReplayLabProperties.SourceCandidateRepository backend =
                new ReplayLabProperties.SourceCandidateRepository();
        backend.setLogicalName("backend");
        backend.setProjectKey("DCE");
        backend.setRepositorySlug("backend");
        backend.setBranch("test2");
        backend.setLanguage("JAVA");
        backend.setAllowedExtensions(List.of(".java"));

        ReplayLabProperties.SourceCandidateRepository customerUi =
                new ReplayLabProperties.SourceCandidateRepository();
        customerUi.setLogicalName("customer-ui");
        customerUi.setProjectKey("DCE");
        customerUi.setRepositorySlug("customer-ui");
        customerUi.setBranch("test2");
        customerUi.setLanguage("TYPESCRIPT");
        customerUi.setAllowedExtensions(List.of(".ts", ".tsx"));

        bitbucket.getRepositories().put("backend", backend);
        bitbucket.getRepositories().put("customerUi", customerUi);
        target.setBitbucket(bitbucket);
        properties.getTargets().put("bss-monolith", target);
        return target;
    }

    private void sourceEvidence(
            String filePath,
            String className,
            String methodName,
            String language
    ) {
        evidence("""
                {
                  "suspectChanges": [
                    {
                      "filePath": "%s",
                      "className": "%s",
                      "methodName": "%s",
                      "language": "%s"
                    }
                  ]
                }
                """.formatted(
                filePath.replace("\\", "\\\\"),
                className,
                methodName,
                language
        ));
    }

    private void evidence(String json) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.SOURCE_CONTEXT);
        evidence.setSource("test");
        evidence.setContentText(json);
        evidence.setSanitized(true);
        evidence.setCreatedAt(Instant.parse("2026-06-23T06:00:00Z"));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.SOURCE_CONTEXT
        )).thenReturn(List.of(evidence));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.AI_ROOT_CAUSE
        )).thenReturn(List.of());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.DETERMINISTIC_ROOT_CAUSE
        )).thenReturn(List.of());
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}

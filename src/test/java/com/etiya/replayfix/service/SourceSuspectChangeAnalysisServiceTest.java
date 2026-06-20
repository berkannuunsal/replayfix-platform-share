package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSuspectChangeAnalysisServiceTest {

    @TempDir
    Path temporaryDirectory;

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private SuspectSignalExtractionService signalExtractionService;
    private CompanySourceReasoningService companyReasoningService;
    private SourceSuspectChangeAnalysisService service;
    private ReplayFixProperties properties;
    private SourceFlowAnchorExtractionService anchorExtractionService;
    private FlowAwareSourceDiscoveryService discoveryService;
    private SourceCandidateGitHistoryService gitHistoryService;
    private SourceReasoningContextBuilder contextBuilder;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        signalExtractionService = mock(SuspectSignalExtractionService.class);
        companyReasoningService = mock(CompanySourceReasoningService.class);
        properties = new ReplayFixProperties();
        properties.setWorkspaceDir(temporaryDirectory.resolve("work").toString());
        objectMapper = new ObjectMapper().findAndRegisterModules();
        anchorExtractionService = new SourceFlowAnchorExtractionService();
        discoveryService = new FlowAwareSourceDiscoveryService();
        gitHistoryService = new SourceCandidateGitHistoryService(
                properties,
                new EvidenceSanitizer()
        );
        contextBuilder = new SourceReasoningContextBuilder(
                evidenceRepository,
                new EvidenceSanitizer(),
                objectMapper
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.REPOSITORY_RESOLUTION
        )).thenReturn(List.of(repositoryResolution()));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.ROVO_RCA
        )).thenReturn(List.of(rovoEvidence()));
        when(signalExtractionService.extract(caseId, false))
                .thenReturn(signalResponse());

        service = service(discoveryService, gitHistoryService, contextBuilder);
    }

    @Test
    void endpointStatusRemainsHypothesisAndCompanyUnavailableWarning()
            throws Exception {
        writeWorkspaceFile();
        when(companyReasoningService.reason(
                org.mockito.ArgumentMatchers.eq(caseId),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new CompanySourceReasoningService.ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE)
        ));

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                true
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.branch()).isEqualTo("test2");
        assertThat(response.llmUsed()).isFalse();
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
    }

    @Test
    void serviceReturnsWarningWhenSourceDiscoveryThrows() throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService throwingDiscovery =
                mock(FlowAwareSourceDiscoveryService.class);
        when(throwingDiscovery.discover(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom stack trace"));
        service = service(throwingDiscovery, gitHistoryService, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService.SOURCE_DISCOVERY_FAILED);
        assertThat(response.toString())
                .doesNotContain("IllegalStateException")
                .doesNotContain("boom stack trace");
    }

    @Test
    void endpointStatusRemainsHypothesisWhenGitHistoryThrows()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService throwingGit =
                mock(SourceCandidateGitHistoryService.class);
        when(discovery.discover(any(), any(), anyInt()))
                .thenReturn(discoveryResult());
        when(throwingGit.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenThrow(new IllegalStateException("git failed"));
        service = service(discovery, throwingGit, contextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService.SOURCE_GIT_HISTORY_FAILED);
    }

    @Test
    void endpointStatusRemainsHypothesisWhenContextBuilderThrows()
            throws Exception {
        writeWorkspaceFile();
        FlowAwareSourceDiscoveryService discovery =
                mock(FlowAwareSourceDiscoveryService.class);
        SourceCandidateGitHistoryService gitHistory =
                mock(SourceCandidateGitHistoryService.class);
        SourceReasoningContextBuilder throwingContextBuilder =
                mock(SourceReasoningContextBuilder.class);
        when(discovery.discover(any(), any(), anyInt()))
                .thenReturn(discoveryResult());
        when(gitHistory.collect(
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyBoolean()
        )).thenReturn(new SourceCandidateGitHistoryService.HistoryResult(
                List.of(),
                List.of(),
                List.of()
        ));
        when(throwingContextBuilder.build(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("context failed"));
        service = service(discovery, gitHistory, throwingContextBuilder);

        var response = service.analyze(
                caseId,
                45,
                20,
                10,
                false,
                false
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.partial()).isTrue();
        assertThat(response.sourceReasoningContext()).isNotNull();
        assertThat(response.warnings())
                .contains(SourceSuspectChangeAnalysisService
                        .SOURCE_REASONING_CONTEXT_FAILED);
    }

    private void writeWorkspaceFile() throws Exception {
        Path file = Path.of(
                properties.getWorkspaceDir(),
                caseId.toString(),
                "repositories",
                "backend",
                "src/main/java/com/example/BusinessFlowController.java"
        );
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;
                public class BusinessFlowController {
                    @PostMapping("/businessFlow/initialize")
                    public void initialize() {}
                }
                """);
    }

    private FlowAwareSourceDiscoveryService.DiscoveryResult discoveryResult() {
        return new FlowAwareSourceDiscoveryService.DiscoveryResult(
                List.of(new SourceCandidateFlowChainItem(
                        "CONTROLLER",
                        "src/main/java/com/example/BusinessFlowController.java",
                        "BusinessFlowController",
                        "initialize",
                        List.of("/businessFlow/initialize"),
                        "Controller mapping annotation matched endpoint anchor.",
                        "HYPOTHESIS"
                )),
                List.of("src/main/java/com/example/BusinessFlowController.java"),
                List.of(),
                java.util.Map.of()
        );
    }

    private SourceSuspectChangeAnalysisService service(
            FlowAwareSourceDiscoveryService discovery,
            SourceCandidateGitHistoryService gitHistory,
            SourceReasoningContextBuilder builder
    ) {
        return new SourceSuspectChangeAnalysisService(
                caseRepository,
                evidenceRepository,
                signalExtractionService,
                anchorExtractionService,
                discovery,
                gitHistory,
                builder,
                companyReasoningService,
                objectMapper,
                properties
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        replayCase.setSourceCommit("abc123");
        return replayCase;
    }

    private SuspectSignalExtractionResponse signalResponse() {
        return new SuspectSignalExtractionResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "",
                List.of(new SuspectSourceSignal(
                        "/businessFlow/initialize",
                        SuspectSignalCategory.ENDPOINT,
                        SuspectSignalStrength.STRONG,
                        List.of("ROVO_RCA"),
                        "test"
                )),
                0,
                List.of()
        );
    }

    private EvidenceEntity repositoryResolution() {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.REPOSITORY_RESOLUTION);
        evidence.setSource("test");
        evidence.setContentText("""
                {
                  "projectKey": "DCE",
                  "primaryRepositorySlug": "backend",
                  "sourceBranch": "test2"
                }
                """);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }

    private EvidenceEntity rovoEvidence() {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.ROVO_RCA);
        evidence.setSource("test");
        evidence.setContentText("{\"summary\":\"flow\"}");
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

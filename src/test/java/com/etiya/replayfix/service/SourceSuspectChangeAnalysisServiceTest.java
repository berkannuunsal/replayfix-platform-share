package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
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

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        signalExtractionService = mock(SuspectSignalExtractionService.class);
        companyReasoningService = mock(CompanySourceReasoningService.class);
        properties = new ReplayFixProperties();
        properties.setWorkspaceDir(temporaryDirectory.resolve("work").toString());

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

        service = new SourceSuspectChangeAnalysisService(
                caseRepository,
                evidenceRepository,
                signalExtractionService,
                new SourceFlowAnchorExtractionService(),
                new FlowAwareSourceDiscoveryService(),
                new SourceCandidateGitHistoryService(
                        properties,
                        new EvidenceSanitizer()
                ),
                new SourceReasoningContextBuilder(
                        evidenceRepository,
                        new EvidenceSanitizer(),
                        new ObjectMapper().findAndRegisterModules()
                ),
                companyReasoningService,
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
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

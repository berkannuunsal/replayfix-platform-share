package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.model.SourceWorkspacePreparationResponse;
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
import static org.mockito.Mockito.when;

class SourceWorkspacePreparationServiceTest {

    @TempDir
    Path temporaryDirectory;

    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayLabProperties properties;
    private SourceWorkspacePreparationService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        properties = new ReplayLabProperties();
        properties.setWorkspaceDir(
                temporaryDirectory.resolve("work").toString()
        );
        service = new SourceWorkspacePreparationService(
                caseRepository,
                evidenceRepository,
                properties,
                new ObjectMapper().findAndRegisterModules()
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.REPOSITORY_RESOLUTION
        )).thenReturn(List.of(repositoryResolution()));
    }

    @Test
    void extractsSourceBranchFromRepositoryResolutionEvidence() {
        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, false);

        assertThat(response.branch()).isEqualTo("test2");
        assertThat(response.repository()).isEqualTo("DCE/backend");
        assertThat(response.repositorySlug()).isEqualTo("backend");
    }

    @Test
    void preparesWorkspacePathUnderRepositoriesSlug() {
        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, false);

        assertThat(response.workspacePath())
                .endsWith(Path.of(
                        "work",
                        caseId.toString(),
                        "repositories",
                        "backend"
                ).toString().replace('\\', '/'));
    }

    @Test
    void doesNotRecloneWhenWorkspaceAlreadyHasSupportedFiles()
            throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(
                workspace.resolve("src/main/java/RegionService.java"),
                "class RegionService {}\n"
        );

        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, false);

        assertThat(response.workspaceReady()).isTrue();
        assertThat(response.cloned()).isFalse();
        assertThat(response.supportedFileCount()).isEqualTo(1);
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void returnsWorkspaceExistsButEmptyUnlessForceIsTrue()
            throws Exception {
        Files.createDirectories(workspace());

        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, false);

        assertThat(response.workspaceReady()).isFalse();
        assertThat(response.cloned()).isFalse();
        assertThat(response.warnings())
                .contains(SourceWorkspacePreparationService
                        .WORKSPACE_EXISTS_BUT_EMPTY);
    }

    @Test
    void forceEmptyWorkspaceContinuesToClonePreparation()
            throws Exception {
        Files.createDirectories(workspace());

        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, true);

        assertThat(response.warnings())
                .doesNotContain(SourceWorkspacePreparationService
                        .WORKSPACE_EXISTS_BUT_EMPTY);
        assertThat(response.warnings())
                .contains(SourceWorkspacePreparationService
                        .SOURCE_REPOSITORY_CREDENTIALS_NOT_CONFIGURED);
    }

    @Test
    void returnsControlledWarningWhenCloneUrlOrCredentialsUnavailable() {
        SourceWorkspacePreparationResponse response =
                service.prepare(caseId, false);

        assertThat(response.workspaceReady()).isFalse();
        assertThat(response.cloned()).isFalse();
        assertThat(response.warnings())
                .contains(SourceWorkspacePreparationService
                        .SOURCE_REPOSITORY_CREDENTIALS_NOT_CONFIGURED);
    }

    private Path workspace() {
        return Path.of(
                properties.getWorkspaceDir(),
                caseId.toString(),
                "repositories",
                "backend"
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        return replayCase;
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
                  "repositoryName": "Backend",
                  "sourceBranch": "test2",
                  "candidates": [
                    {
                      "projectKey": "DCE",
                      "slug": "backend",
                      "name": "Backend",
                      "cloneUrl": "https://bitbucket.example/scm/dce/backend.git"
                    }
                  ]
                }
                """);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

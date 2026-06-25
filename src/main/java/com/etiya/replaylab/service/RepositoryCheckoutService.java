package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.RepositoryCandidate;
import com.etiya.replaylab.model.RepositoryResolutionResult;
import com.etiya.replaylab.model.SourceCheckoutResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RepositoryCheckoutService {

    private final ReplayCaseService caseService;
    private final EvidenceService evidenceService;
    private final GitWorkspaceService gitWorkspaceService;
    private final ObjectMapper objectMapper;

    public RepositoryCheckoutService(
            ReplayCaseService caseService,
            EvidenceService evidenceService,
            GitWorkspaceService gitWorkspaceService,
            ObjectMapper objectMapper
    ) {
        this.caseService = caseService;
        this.evidenceService = evidenceService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.objectMapper = objectMapper;
    }

    public SourceCheckoutResult checkout(
            UUID caseId
    ) {
        var replayCase = caseService.get(caseId);

        RepositoryResolutionResult resolution =
                readResolution(caseId);

        RepositoryCandidate primaryCandidate =
                resolution.candidates()
                        .stream()
                        .filter(candidate ->
                                candidate.slug().equals(
                                        resolution
                                                .primaryRepositorySlug()
                                )
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Primary repository candidate "
                                                + "was not found."
                                )
                        );

        SourceCheckoutResult result =
                gitWorkspaceService.prepareReadOnly(
                        replayCase,
                        primaryCandidate
                );

        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.SOURCE_CHECKOUT,
                    "bitbucket-read-only-checkout",
                    objectMapper.writeValueAsString(
                            result
                    ),
                    true
            );

            return result;

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save source checkout evidence.",
                    exception
            );
        }
    }

    private RepositoryResolutionResult readResolution(
            UUID caseId
    ) {
        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
                        .filter(item ->
                                item.getEvidenceType()
                                        == EvidenceType
                                        .REPOSITORY_RESOLUTION
                        )
                        .reduce((first, second) -> second)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Repository resolution "
                                                + "not found for case: "
                                                + caseId
                                )
                        );

        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    RepositoryResolutionResult.class
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse repository resolution.",
                    exception
            );
        }
    }
}

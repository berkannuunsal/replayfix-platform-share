package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record JenkinsSourceReanalysisResult(
        UUID caseId,
        String repositorySlug,
        String previousCommitSha,
        String jenkinsCommitSha,
        String workspace,
        boolean commitFetched,
        SourceContextResult sourceContext,
        List<String> warnings
) {
}

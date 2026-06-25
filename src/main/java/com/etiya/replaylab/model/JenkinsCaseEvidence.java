package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record JenkinsCaseEvidence(
        UUID caseId,
        String applicationKey,
        String repositorySlug,
        JenkinsBuildSnapshot build,
        JenkinsBuildSnapshot image,
        List<String> warnings
) {
}

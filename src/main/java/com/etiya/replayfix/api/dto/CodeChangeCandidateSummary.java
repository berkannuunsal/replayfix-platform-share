package com.etiya.replayfix.api.dto;

import java.util.List;

public record CodeChangeCandidateSummary(
        String sourceCandidateSource,
        String repositoryLogicalName,
        String projectKey,
        String repositorySlug,
        String branch,
        String filePath,
        String normalizedFilePath,
        String classOrComponentName,
        String methodName,
        String language,
        boolean snippetAvailable,
        int snippetChars,
        String snippetPreview,
        List<String> missingEvidence,
        List<String> warnings
) {
    public CodeChangeCandidateSummary {
        sourceCandidateSource = sourceCandidateSource == null
                ? ""
                : sourceCandidateSource;
        repositoryLogicalName = repositoryLogicalName == null
                ? ""
                : repositoryLogicalName;
        projectKey = projectKey == null ? "" : projectKey;
        repositorySlug = repositorySlug == null ? "" : repositorySlug;
        branch = branch == null ? "" : branch;
        normalizedFilePath = normalizedFilePath == null
                ? ""
                : normalizedFilePath;
        snippetPreview = snippetPreview == null ? "" : snippetPreview;
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public CodeChangeCandidateSummary(
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            boolean snippetAvailable,
            int snippetChars,
            String snippetPreview,
            List<String> missingEvidence,
            List<String> warnings
    ) {
        this(
                "",
                "",
                "",
                "",
                "",
                filePath,
                "",
                classOrComponentName,
                methodName,
                language,
                snippetAvailable,
                snippetChars,
                snippetPreview,
                missingEvidence,
                warnings
        );
    }
}

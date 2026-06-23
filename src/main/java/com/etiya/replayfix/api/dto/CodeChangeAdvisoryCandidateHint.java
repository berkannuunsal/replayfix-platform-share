package com.etiya.replayfix.api.dto;

import java.util.List;

public record CodeChangeAdvisoryCandidateHint(
        String repositoryLogicalName,
        String filePath,
        String classOrComponentName,
        String methodName,
        String language,
        String codeSnippet,
        String relatedDtoSnippet,
        String relatedLogSummary,
        List<String> constraints
) {
    public CodeChangeAdvisoryCandidateHint {
        repositoryLogicalName = repositoryLogicalName == null
                ? ""
                : repositoryLogicalName;
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public CodeChangeAdvisoryCandidateHint(
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            String codeSnippet,
            String relatedDtoSnippet,
            String relatedLogSummary,
            List<String> constraints
    ) {
        this(
                "",
                filePath,
                classOrComponentName,
                methodName,
                language,
                codeSnippet,
                relatedDtoSnippet,
                relatedLogSummary,
                constraints
        );
    }
}

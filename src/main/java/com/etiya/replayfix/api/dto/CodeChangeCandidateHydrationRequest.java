package com.etiya.replayfix.api.dto;

import java.util.List;

public record CodeChangeCandidateHydrationRequest(
        List<CodeChangeAdvisoryCandidateHint> candidateHints,
        Boolean includeSnippetPreview,
        Integer maxSnippetChars
) {
    public CodeChangeCandidateHydrationRequest {
        candidateHints = candidateHints == null
                ? List.of()
                : List.copyOf(candidateHints);
    }
}

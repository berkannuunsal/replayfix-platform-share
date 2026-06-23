package com.etiya.replayfix.api.dto;

import java.util.List;

public record CodeChangeAdvisoryOrchestrationRequest(
        String problemSummary,
        String expectedBehavior,
        String actualBehavior,
        List<String> advisoryModes,
        List<CodeChangeAdvisoryCandidateHint> candidateHints,
        Boolean useLatestSanitizedReplayInput,
        Boolean useLatestSourceAnalysisIfAvailable
) {
    public CodeChangeAdvisoryOrchestrationRequest {
        advisoryModes = advisoryModes == null
                ? List.of()
                : List.copyOf(advisoryModes);
        candidateHints = candidateHints == null
                ? List.of()
                : List.copyOf(candidateHints);
    }
}

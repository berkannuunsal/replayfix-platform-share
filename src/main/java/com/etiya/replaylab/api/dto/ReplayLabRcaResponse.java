package com.etiya.replaylab.api.dto;

import java.util.List;

public record ReplayLabRcaResponse(
        String status,
        String confidence,
        String probableRootCause,
        List<String> knownFacts,
        List<String> hypotheses,
        List<String> missingEvidence,
        String recommendedNextAction
) {
}

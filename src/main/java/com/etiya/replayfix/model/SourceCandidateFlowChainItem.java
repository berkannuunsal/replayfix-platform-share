package com.etiya.replayfix.model;

import java.util.List;

public record SourceCandidateFlowChainItem(
        String layer,
        String file,
        String className,
        String methodName,
        List<String> relatedSignals,
        String reason,
        String status
) {
}

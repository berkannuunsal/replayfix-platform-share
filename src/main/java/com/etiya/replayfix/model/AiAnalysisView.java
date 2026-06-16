package com.etiya.replayfix.model;

import java.util.List;

public record AiAnalysisView(
        boolean available,
        boolean enabled,
        boolean canGenerate,
        String provider,
        String model,
        StructuredAiRootCauseAnalysis analysis,
        String unavailableReason
) {
    public static AiAnalysisView disabled() {
        return new AiAnalysisView(
                false,
                false,
                false,
                "DISABLED",
                null,
                null,
                "AI integration is disabled"
        );
    }

    public static AiAnalysisView unavailable(String reason) {
        return new AiAnalysisView(
                false,
                true,
                false,
                null,
                null,
                null,
                reason
        );
    }

    public static AiAnalysisView available(
            String provider,
            String model,
            StructuredAiRootCauseAnalysis analysis,
            boolean canGenerate
    ) {
        return new AiAnalysisView(
                true,
                true,
                canGenerate,
                provider,
                model,
                analysis,
                null
        );
    }
}

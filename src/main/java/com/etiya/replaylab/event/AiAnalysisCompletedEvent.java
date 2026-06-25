package com.etiya.replaylab.event;

import com.etiya.replaylab.domain.NotificationType;
import com.etiya.replaylab.domain.ReplayCaseEntity;

import java.util.UUID;

public record AiAnalysisCompletedEvent(
        UUID caseId,
        String jiraKey,
        NotificationType notificationType,
        String title,
        String message,
        String severity,
        boolean synthetic
) {
    public static AiAnalysisCompletedEvent success(
            ReplayCaseEntity caseEntity,
            double confidence,
            boolean synthetic
    ) {
        return new AiAnalysisCompletedEvent(
                caseEntity.getId(),
                caseEntity.getJiraKey(),
                NotificationType.AI_ANALYSIS_COMPLETED,
                "AI analysis completed",
                String.format("%s analysis available for review. Confidence: %.0f%%",
                        synthetic ? "Synthetic" : "AI",
                        confidence * 100),
                synthetic ? "INFO" : "SUCCESS",
                synthetic
        );
    }

    public static AiAnalysisCompletedEvent failure(
            ReplayCaseEntity caseEntity,
            String errorMessage
    ) {
        return new AiAnalysisCompletedEvent(
                caseEntity.getId(),
                caseEntity.getJiraKey(),
                NotificationType.AI_ANALYSIS_FAILED,
                "AI analysis failed",
                errorMessage,
                "ERROR",
                false
        );
    }
}

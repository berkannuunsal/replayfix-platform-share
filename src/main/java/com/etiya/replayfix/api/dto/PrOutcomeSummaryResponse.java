package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PrOutcomeSummaryResponse(
        UUID caseId,
        String pullRequestId,
        String pullRequestUrl,
        String summaryPreview,
        boolean prCommentCreated,
        String prSummaryCommentUrl,
        boolean prDescriptionUpdated,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}

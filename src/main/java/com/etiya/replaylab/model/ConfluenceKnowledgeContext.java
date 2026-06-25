package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record ConfluenceKnowledgeContext(
        UUID caseId,
        String applicationKey,
        ConfluenceKnowledgeQueryPlan queryPlan,
        int searchedQueryCount,
        int totalSearchHitCount,
        int selectedPageCount,
        List<ConfluenceSearchHit> selectedHits,
        List<ConfluencePageDocument> pages,
        int totalIncludedChars,
        List<String> warnings
) {
}

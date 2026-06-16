package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record ConfluenceKnowledgeQueryPlan(
        UUID caseId,
        List<String> searchTerms,
        List<String> serviceTerms,
        List<String> errorTerms,
        List<String> endpointTerms,
        List<String> allowedSpaceKeys,
        List<ConfluencePlannedQuery> queries,
        List<String> warnings
) {
}

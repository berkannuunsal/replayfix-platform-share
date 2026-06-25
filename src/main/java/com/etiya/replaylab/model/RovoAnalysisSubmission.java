package com.etiya.replaylab.model;

import java.util.List;

/**
 * Rovo Incident Commander RCA submission.
 * POST /api/v1/rovo/incidents/{jiraKey}/analysis request model.
 */
public record RovoAnalysisSubmission(
        String executiveSummary,
        List<String> probableFailureChain,
        String probableRootCause,
        String impactedComponent,
        double confidence,
        List<CompetingHypothesis> competingHypotheses,
        List<SimilarIncident> similarIncidents,
        List<String> confluenceReferences,
        String regressionTestHypothesis,
        String minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction
) {
    public record CompetingHypothesis(
            String hypothesis,
            double likelihood,
            String supportingEvidence,
            String contradictingEvidence
    ) {}

    public record SimilarIncident(
            String jiraKey,
            String summary,
            double similarity,
            String resolution
    ) {}
}

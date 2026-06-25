package com.etiya.replaylab.model;

import java.util.List;

public record AiEvidenceBundle(
        String jiraKey,
        String summary,
        String plainDescription,
        DeterministicRootCauseReport deterministicReport,
        CorrelationSignals correlations,
        List<IncidentTimelineEvent> timelineEvents,
        TempoSummary tempo,
        List<KnowledgeExcerpt> knowledge,
        List<SourceExcerpt> sourceExcerpts,
        List<String> guardrails
) {

    public record TempoSummary(
            int requestedTraceCount,
            int foundTraceCount,
            List<String> foundTraceIds,
            List<String> errors
    ) {
    }

    public record KnowledgeExcerpt(
            String source,
            String title,
            String content,
            String url
    ) {
    }

    public record SourceExcerpt(
            String path,
            String content
    ) {
    }
}

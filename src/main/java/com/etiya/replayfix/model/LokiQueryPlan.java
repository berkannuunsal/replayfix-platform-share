package com.etiya.replayfix.model;

import java.util.List;

public record LokiQueryPlan(
    String jiraKey,
    String summary,
    String plainDescription,
    IncidentSignals signals,
    List<LokiQueryCandidate> queries
) {
}

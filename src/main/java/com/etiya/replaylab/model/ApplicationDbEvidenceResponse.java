package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record ApplicationDbEvidenceResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String dataSourceKey,
        boolean readOnly,
        List<ApplicationDbEvidenceQueryTemplate> queryTemplates,
        List<ApplicationDbEvidenceQueryResult> executedQueries,
        List<ApplicationDbEvidenceFinding> findings,
        boolean masked,
        List<String> warnings
) {
    public ApplicationDbEvidenceResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        dataSourceKey = dataSourceKey == null ? "backend" : dataSourceKey;
        queryTemplates = queryTemplates == null ? List.of() : List.copyOf(queryTemplates);
        executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
        findings = findings == null ? List.of() : List.copyOf(findings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

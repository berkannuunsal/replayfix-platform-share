package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record ApplicationDbEvidenceQueryResult(
        String templateId,
        String sqlPreview,
        int rowCount,
        List<Map<String, Object>> rows,
        boolean masked,
        List<String> warnings
) {
    public ApplicationDbEvidenceQueryResult {
        templateId = templateId == null ? "UNKNOWN" : templateId;
        sqlPreview = sqlPreview == null ? "" : sqlPreview;
        rows = rows == null ? List.of() : List.copyOf(rows);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

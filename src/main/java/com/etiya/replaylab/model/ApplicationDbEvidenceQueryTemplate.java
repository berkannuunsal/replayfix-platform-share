package com.etiya.replaylab.model;

import java.util.List;

public record ApplicationDbEvidenceQueryTemplate(
        String templateId,
        String name,
        String purpose,
        List<String> requiredInputs,
        List<String> tables,
        List<String> columns,
        String sqlPreview,
        List<String> maskedFields,
        boolean readOnly
) {
    public ApplicationDbEvidenceQueryTemplate {
        templateId = templateId == null ? "UNKNOWN" : templateId;
        name = name == null ? "" : name;
        purpose = purpose == null ? "" : purpose;
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        tables = tables == null ? List.of() : List.copyOf(tables);
        columns = columns == null ? List.of() : List.copyOf(columns);
        sqlPreview = sqlPreview == null ? "" : sqlPreview;
        maskedFields = maskedFields == null ? List.of() : List.copyOf(maskedFields);
    }
}

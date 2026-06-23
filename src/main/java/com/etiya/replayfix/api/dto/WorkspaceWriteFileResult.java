package com.etiya.replayfix.api.dto;

import java.util.List;

public record WorkspaceWriteFileResult(
        String fileType,
        String relativePath,
        String status,
        boolean writeAllowed,
        int contentChars,
        String contentPreview,
        List<String> warnings
) {
    public WorkspaceWriteFileResult {
        fileType = fileType == null ? "UNKNOWN" : fileType;
        relativePath = relativePath == null ? "" : relativePath;
        status = status == null ? "DRAFT" : status;
        contentPreview = contentPreview == null ? "" : contentPreview;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

package com.etiya.replayfix.api.dto;

import java.util.List;

public record GeneratedDraftReviewedFile(
        String fileType,
        String relativePath,
        boolean exists,
        boolean pathSafe,
        int contentChars,
        String contentPreview,
        List<String> securityFindings,
        List<String> qualityFindings,
        List<String> warnings
) {
    public GeneratedDraftReviewedFile {
        fileType = fileType == null ? "UNKNOWN" : fileType;
        relativePath = relativePath == null ? "" : relativePath;
        contentPreview = contentPreview == null ? "" : contentPreview;
        securityFindings = securityFindings == null
                ? List.of()
                : List.copyOf(securityFindings);
        qualityFindings = qualityFindings == null
                ? List.of()
                : List.copyOf(qualityFindings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

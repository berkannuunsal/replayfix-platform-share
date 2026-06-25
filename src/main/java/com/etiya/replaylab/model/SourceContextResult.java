package com.etiya.replaylab.model;

import com.etiya.replaylab.model.AiEvidenceBundle.SourceExcerpt;

import java.util.List;

public record SourceContextResult(
        String sourceMode,
        String repository,
        int scannedFileCount,
        List<String> searchTerms,
        List<SourceExcerpt> excerpts,
        String warning
) {
}

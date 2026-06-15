package com.etiya.replayfix.model;

import com.etiya.replayfix.model.AiEvidenceBundle.SourceExcerpt;

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

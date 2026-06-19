package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record SuspectSignalExtractionResponse(
        UUID caseId,
        String jiraKey,
        String repository,
        String branch,
        List<SuspectSourceSignal> signals,
        int filteredCount,
        List<String> warnings
) {
}

package com.etiya.replayfix.model;

import com.etiya.replayfix.model.IntegrationModels.LokiLogEntry;

import java.util.List;

public record AdaptiveLokiSearchResult(
    List<LokiSearchAttempt> attempts,
    List<LokiLogEntry> logs
) {
}

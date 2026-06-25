package com.etiya.replaylab.model;

import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;

import java.util.List;

public record AdaptiveLokiSearchResult(
    List<LokiSearchAttempt> attempts,
    List<LokiLogEntry> logs
) {
}

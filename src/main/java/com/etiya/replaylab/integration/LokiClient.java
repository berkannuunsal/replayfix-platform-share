package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;
import java.time.Instant;
import java.util.List;

public interface LokiClient {
    List<LokiLogEntry> queryRange(String logQl, Instant start, Instant end, int limit);
}

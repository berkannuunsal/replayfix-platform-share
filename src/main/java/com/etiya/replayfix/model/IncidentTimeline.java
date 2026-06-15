package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IncidentTimeline(
        Instant startedAt,
        Instant endedAt,
        int eventCount,
        Map<String, Integer> applicationCounts,
        Map<String, Integer> severityCounts,
        Map<String, Integer> httpStatusCounts,
        List<IncidentTimelineEvent> events
) {
}

package com.etiya.replayfix.model;

import java.util.Map;

public record TempoSpanEvent(
        String name,
        String timestamp,
        Map<String, String> attributes
) {
}

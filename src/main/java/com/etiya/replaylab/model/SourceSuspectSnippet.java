package com.etiya.replaylab.model;

import java.util.List;

public record SourceSuspectSnippet(
        int lineNumber,
        String text,
        List<String> matchedSignals
) {
}

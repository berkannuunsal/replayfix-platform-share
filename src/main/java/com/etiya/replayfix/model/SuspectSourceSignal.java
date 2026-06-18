package com.etiya.replayfix.model;

import java.util.List;

public record SuspectSourceSignal(
        String value,
        SuspectSignalCategory category,
        List<String> sourceEvidenceTypes,
        String reason
) {
}

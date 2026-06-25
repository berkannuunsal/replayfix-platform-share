package com.etiya.replaylab.model;

import java.util.List;

public record SuspectSourceSignal(
        String value,
        SuspectSignalCategory category,
        SuspectSignalStrength strength,
        List<String> sourceEvidenceTypes,
        String reason
) {
}

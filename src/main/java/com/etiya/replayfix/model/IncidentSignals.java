package com.etiya.replayfix.model;

import java.util.List;

public record IncidentSignals(
    List<String> endpoints,
    List<String> httpStatuses,
    List<String> errorCodes,
    List<String> businessTerms,
    List<String> statusValues,
    List<String> serviceHints
) {
}

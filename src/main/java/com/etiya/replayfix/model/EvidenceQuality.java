package com.etiya.replayfix.model;

public record EvidenceQuality(
        String jiraSignalQuality,
        int lokiMatchedRows,
        int lokiFailedQueryCount,
        int lokiTotalQueryCount,
        boolean correlationIdFound,
        boolean traceIdFound,
        boolean tempoTraceFound,
        int sourceScannedFileCount,
        int sourceMatchedFileCount,
        double rcaConfidence,
        String rcaClassification,
        String demoReadiness,
        String overallAssessment
) {
}

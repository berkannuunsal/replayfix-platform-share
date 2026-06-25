package com.etiya.replaylab.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Envelope that wraps the complete Rovo RCA import, including:
 * - Raw human-readable report (Turkish text before JSON markers)
 * - Raw JSON from Rovo
 * - Normalized JSON for ReplayLab dashboard
 * - Import metadata
 */
public record RovoRcaEnvelope(
        String schemaVersion,
        UUID caseId,
        String jiraKey,
        String source,
        String commentId,
        String commentAuthor,
        Instant importedAt,
        String importStatus,
        String rcaStatus,
        String rawHumanReport,
        JsonNode rawRovoJson,
        JsonNode normalizedRovoJson,
        List<String> normalizationWarnings
) {
    public static final String ENVELOPE_SCHEMA_VERSION = "1.0";

    public static RovoRcaEnvelope create(
            UUID caseId,
            String jiraKey,
            String commentId,
            String commentAuthor,
            String rawHumanReport,
            String rawRovoJsonText,
            String normalizedRovoJsonText,
            List<String> normalizationWarnings,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) throws Exception {
        JsonNode rawJson = objectMapper.readTree(rawRovoJsonText);
        JsonNode normalizedJson = objectMapper.readTree(normalizedRovoJsonText);
        
        // Extract RCA status from normalized JSON
        String rcaStatus = normalizedJson.has("status") 
                ? normalizedJson.get("status").asText("HYPOTHESIS")
                : "HYPOTHESIS";

        return new RovoRcaEnvelope(
                ENVELOPE_SCHEMA_VERSION,
                caseId,
                jiraKey,
                "rovo-incident-commander",
                commentId,
                commentAuthor,
                Instant.now(),
                "IMPORTED",
                rcaStatus,
                rawHumanReport,
                rawJson,
                normalizedJson,
                normalizationWarnings
        );
    }
}

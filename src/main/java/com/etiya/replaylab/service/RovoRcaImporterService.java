package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.IntegrationModels;
import com.etiya.replaylab.model.RovoRcaAnalysis;
import com.etiya.replaylab.model.RovoRcaEnvelope;
import com.etiya.replaylab.model.RovoRcaImportResponse;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RovoRcaImporterService {

    private static final Logger log = LoggerFactory.getLogger(RovoRcaImporterService.class);

    private static final String RCA_START_MARKER = "REPLAYFIX_ROVO_RCA_V1";
    private static final String RCA_END_MARKER = "REPLAYFIX_ROVO_RCA_END";

    private static final Pattern RCA_BLOCK_PATTERN = Pattern.compile(
            Pattern.quote(RCA_START_MARKER) + "\\s*(.+?)\\s*" + Pattern.quote(RCA_END_MARKER),
            Pattern.DOTALL
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraClient jiraClient;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public RovoRcaImporterService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraClient jiraClient,
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.jiraClient = jiraClient;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    public RovoRcaImportResponse importFromJira(UUID caseId) {
        return importFromJira(caseId, false);
    }

    public RovoRcaImportResponse importFromJira(UUID caseId, boolean force) {
        // Initialize diagnostics tracking
        int commentsScanned = 0;
        int pagesScanned = 0;
        int markerStartFoundCount = 0;
        int markerEndFoundCount = 0;
        List<String> candidateCommentIds = new ArrayList<>();
        String latestCommentCreatedAt = null;
        String latestCommentAuthor = null;
        List<String> detectedBodyFormats = new ArrayList<>();
        List<Integer> normalizedTextLengths = new ArrayList<>();
        String importedCommentId = null;
        String importedBodyFormat = null;

        try {
            log.info("ROVO_IMPORT_START caseId={} force={}", caseId, force);
            ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

            String jiraKey = caseEntity.getJiraKey();
            if (jiraKey == null || jiraKey.isBlank()) {
                throw new IllegalStateException("Case has no Jira key");
            }

            // Fetch all Jira comments (with pagination)
            List<IntegrationModels.JiraComment> comments = jiraClient.getComments(jiraKey);
            commentsScanned = comments.size();
            pagesScanned = (commentsScanned + 99) / 100; // Estimate pages
            
            log.info("ROVO_IMPORT_COMMENTS_FETCHED jiraKey={} commentsScanned={} estimatedPages={}", 
                    jiraKey, commentsScanned, pagesScanned);

            // Track latest comment and scan for markers
            if (!comments.isEmpty()) {
                IntegrationModels.JiraComment latestComment = comments.get(comments.size() - 1);
                latestCommentAuthor = latestComment.author();
                latestCommentCreatedAt = latestComment.created() != null ? latestComment.created().toString() : null;

                // Scan all comments and collect diagnostics
                for (IntegrationModels.JiraComment comment : comments) {
                    String body = comment.body();
                    if (body != null) {
                        // Detect body format
                        String format = detectBodyFormat(body);
                        detectedBodyFormats.add(format);
                        normalizedTextLengths.add(body.length());
                        
                        log.debug("ROVO_IMPORT_COMMENT_NORMALIZED commentId={} bodyFormat={} normalizedLength={}", 
                                comment.id(), format, body.length());
                        
                        // Check for markers
                        boolean hasStartMarker = body.contains(RCA_START_MARKER);
                        boolean hasEndMarker = body.contains(RCA_END_MARKER);
                        
                        if (hasStartMarker) markerStartFoundCount++;
                        if (hasEndMarker) markerEndFoundCount++;
                        
                        if (hasStartMarker && hasEndMarker) {
                            candidateCommentIds.add(comment.id());
                            log.info("ROVO_IMPORT_MARKERS_FOUND commentId={} startFound=true endFound=true", 
                                    comment.id());
                        } else if (hasStartMarker || hasEndMarker) {
                            log.debug("ROVO_IMPORT_MARKERS_FOUND commentId={} startFound={} endFound={}", 
                                    comment.id(), hasStartMarker, hasEndMarker);
                        }
                    }
                }
            }

            // Extract latest Rovo RCA from comments
            RovoRcaBlock rovoRcaBlock = extractLatestRovoRcaFromComments(comments);

            if (rovoRcaBlock == null) {
                log.warn("ROVO_IMPORT_NOT_FOUND caseId={} jiraKey={} commentsScanned={} markerStartFound={} markerEndFound={} candidateComments={}",
                        caseId, jiraKey, commentsScanned, markerStartFoundCount, markerEndFoundCount, candidateCommentIds.size());
                
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, null, null
                );
                return RovoRcaImportResponse.notFound(caseId, jiraKey, diagnostics);
            }

            // Set diagnostics from found block
            importedCommentId = rovoRcaBlock.commentId;
            importedBodyFormat = rovoRcaBlock.bodyFormat;

            // Normalize and parse Rovo RCA JSON
            List<String> normalizationWarnings = new ArrayList<>();
            String normalizedJson;
            boolean wasNormalized = false;
            
            try {
                // Parse as JsonNode first for normalization
                com.fasterxml.jackson.databind.JsonNode rawJson = objectMapper.readTree(rovoRcaBlock.json);
                com.fasterxml.jackson.databind.JsonNode normalizedJsonNode = normalizeRovoRcaJson(rawJson, normalizationWarnings);
                
                wasNormalized = !normalizationWarnings.isEmpty();
                normalizedJson = objectMapper.writeValueAsString(normalizedJsonNode);
                
                if (wasNormalized) {
                    log.info("ROVO_IMPORT_NORMALIZED commentId={} warnings={}", importedCommentId, normalizationWarnings.size());
                }
            } catch (Exception e) {
                log.error("Failed to normalize Rovo RCA JSON: commentId={} error={}", importedCommentId, e.getMessage());
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, e.getMessage(), diagnostics);
            }
            
            // Parse normalized JSON into DTO
            RovoRcaAnalysis rovoRca;
            try {
                rovoRca = objectMapper.readValue(normalizedJson, RovoRcaAnalysis.class);
            } catch (Exception e) {
                log.error("Failed to parse normalized Rovo RCA JSON: commentId={} error={}", importedCommentId, e.getMessage());
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, e.getMessage(), diagnostics);
            }

            // Validate schema version
            if (rovoRca.schemaVersion() == null || rovoRca.schemaVersion().isBlank()) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, "Missing schemaVersion", diagnostics);
            }

            // Validate jiraKey match
            if (rovoRca.jiraKey() != null && !rovoRca.jiraKey().equals(jiraKey)) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.jiraKeyMismatch(caseId, jiraKey, rovoRca.jiraKey(), diagnostics);
            }

            // Create envelope wrapping raw report, raw JSON, and normalized JSON
            RovoRcaEnvelope envelope = RovoRcaEnvelope.create(
                    caseId,
                    jiraKey,
                    importedCommentId,
                    rovoRcaBlock.commentAuthor,
                    rovoRcaBlock.rawHumanReport,
                    rovoRcaBlock.json,
                    normalizedJson,
                    normalizationWarnings,
                    objectMapper
            );
            
            String envelopeJson = objectMapper.writeValueAsString(envelope);

            // Check for duplicate using commentId from envelope
            // Create final variables for lambda capture
            final String finalCommentId = importedCommentId;
            final String finalRawJson = rovoRcaBlock.json;
            
            Optional<EvidenceEntity> existingEvidence = evidenceRepository
                    .findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA)
                    .stream()
                    .filter(e -> isSameRovoRcaEvidence(e, finalCommentId, finalRawJson))
                    .findFirst();

            if (existingEvidence.isPresent() && !force) {
                log.info("ROVO_IMPORT_DUPLICATE caseId={} existingEvidenceId={} commentId={}", 
                        caseId, existingEvidence.get().getId(), importedCommentId);
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.duplicate(caseId, jiraKey, existingEvidence.get().getId(), diagnostics);
            }

            EvidenceEntity evidence = existingEvidence
                    .map(existing -> replaceExistingEvidence(existing, envelopeJson, rovoRca.confidence()))
                    .orElseGet(() -> evidenceService.save(
                            caseId,
                            EvidenceType.ROVO_RCA,
                            "rovo-incident-commander",
                            envelopeJson,
                            true
                    ));

            UUID evidenceId = evidence.getId();

            log.info("ROVO_IMPORT_SUCCESS caseId={} evidenceId={} commentId={} bodyFormat={} confidence={}",
                    caseId, evidenceId, importedCommentId, importedBodyFormat, rovoRca.confidence());

            RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                    commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                    candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                    detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
            );

            return RovoRcaImportResponse.success(
                    caseId,
                    jiraKey,
                    evidenceId,
                    importedCommentId,
                    rovoRca.status(),
                    rovoRca.confidence(),
                    rovoRca.probableRootCause(),
                    diagnostics,
                    wasNormalized,
                    normalizationWarnings
            );

        } catch (Exception e) {
            log.error("ROVO_IMPORT_ERROR caseId={} error={}", caseId, e.getMessage());
            RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                    commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                    candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                    detectedBodyFormats, normalizedTextLengths, null, null
            );
            return RovoRcaImportResponse.error(caseId, null, e.getMessage(), diagnostics);
        }
    }

    private String detectBodyFormat(String body) {
        if (body == null) {
            return "UNKNOWN";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.contains("\"type\":")) {
            return "ADF";
        }
        return "PLAIN_TEXT";
    }

    public RovoRcaImportResponse importManual(UUID caseId, String rawComment) {
        return importManual(caseId, rawComment, false);
    }

    public RovoRcaImportResponse importManual(UUID caseId, String rawComment, boolean force) {
        List<String> formats = List.of("MANUAL");
        List<Integer> lengths = List.of(rawComment != null ? rawComment.length() : 0);
        
        try {
            ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

            String jiraKey = caseEntity.getJiraKey();

            String rovoRcaJson = extractRovoRcaJson(rawComment);

            if (rovoRcaJson == null) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, null
                );
                return RovoRcaImportResponse.notFound(caseId, jiraKey, diagnostics);
            }

            List<String> normalizationWarnings = new ArrayList<>();
            String normalizedJson;
            RovoRcaAnalysis rovoRca;
            try {
                com.fasterxml.jackson.databind.JsonNode rawJson = objectMapper.readTree(rovoRcaJson);
                com.fasterxml.jackson.databind.JsonNode normalizedJsonNode = normalizeRovoRcaJson(rawJson, normalizationWarnings);
                normalizedJson = objectMapper.writeValueAsString(normalizedJsonNode);
                rovoRca = objectMapper.readValue(normalizedJson, RovoRcaAnalysis.class);
            } catch (Exception e) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, null
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, e.getMessage(), diagnostics);
            }

            if (rovoRca.schemaVersion() == null || rovoRca.schemaVersion().isBlank()) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, "MANUAL"
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, "Missing schemaVersion", diagnostics);
            }

            if (rovoRca.jiraKey() != null && !rovoRca.jiraKey().equals(jiraKey)) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, "MANUAL"
                );
                return RovoRcaImportResponse.jiraKeyMismatch(caseId, jiraKey, rovoRca.jiraKey(), diagnostics);
            }

            String rawHumanReport = extractHumanReport(rawComment);
            RovoRcaEnvelope envelope = RovoRcaEnvelope.create(
                    caseId,
                    jiraKey,
                    null,
                    null,
                    rawHumanReport,
                    rovoRcaJson,
                    normalizedJson,
                    normalizationWarnings,
                    objectMapper
            );
            String envelopeJson = objectMapper.writeValueAsString(envelope);

            // Check for duplicate
            String contentHash = calculateHash(rovoRcaJson);
            Optional<EvidenceEntity> existingEvidence = evidenceRepository
                    .findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA)
                    .stream()
                    .filter(e -> contentHash.equals(calculateHash(e.getContentText()))
                            || isSameRovoRcaEvidence(e, null, rovoRcaJson))
                    .findFirst();

            if (existingEvidence.isPresent() && !force) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, "MANUAL"
                );
                return RovoRcaImportResponse.duplicate(caseId, jiraKey, existingEvidence.get().getId(), diagnostics);
            }

            EvidenceEntity evidence = existingEvidence
                    .map(existing -> replaceExistingEvidence(existing, envelopeJson, rovoRca.confidence()))
                    .orElseGet(() -> evidenceService.save(
                            caseId,
                            EvidenceType.ROVO_RCA,
                            "rovo-incident-commander",
                            envelopeJson,
                            true
                    ));

            UUID evidenceId = evidence.getId();

            log.info("Manually imported Rovo RCA: caseId={}, evidenceId={}", caseId, evidenceId);

            RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                    0, 0, 0, 0, List.of(), null, null, formats, lengths, null, "MANUAL"
            );

            return RovoRcaImportResponse.success(
                    caseId,
                    jiraKey,
                    evidenceId,
                    null,
                    rovoRca.status(),
                    rovoRca.confidence(),
                    rovoRca.probableRootCause(),
                    diagnostics,
                    !normalizationWarnings.isEmpty(),
                    normalizationWarnings
            );

        } catch (Exception e) {
            log.error("Failed to manually import Rovo RCA: caseId={}", caseId, e);
            RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                    0, 0, 0, 0, List.of(), null, null, formats, lengths, null, null
            );
            return RovoRcaImportResponse.error(caseId, null, e.getMessage(), diagnostics);
        }
    }

    private RovoRcaBlock extractLatestRovoRcaFromComments(List<IntegrationModels.JiraComment> comments) {
        // Process comments in reverse order (latest first)
        for (int i = comments.size() - 1; i >= 0; i--) {
            IntegrationModels.JiraComment comment = comments.get(i);
            String body = comment.body();
            
            // Detect body format
            String bodyFormat = "PLAIN_TEXT";
            if (body != null && (body.trim().startsWith("{") || body.contains("\"type\":"))) {
                bodyFormat = "ADF";
            }
            
            String json = extractRovoRcaJson(body);
            if (json != null) {
                // Extract human-readable report (text before the marker)
                String rawHumanReport = extractHumanReport(body);
                int normalizedLength = body != null ? body.length() : 0;
                return new RovoRcaBlock(
                        comment.id(),
                        comment.author(),
                        json,
                        rawHumanReport,
                        bodyFormat,
                        normalizedLength
                );
            }
        }
        return null;
    }

    private String extractRovoRcaJson(String text) {
        if (text == null) {
            return null;
        }

        Matcher matcher = RCA_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private String extractHumanReport(String text) {
        if (text == null) {
            return "";
        }

        // Extract everything before the REPLAYFIX_ROVO_RCA_V1 marker
        int markerIndex = text.indexOf(RCA_START_MARKER);
        if (markerIndex > 0) {
            return text.substring(0, markerIndex).trim();
        }

        return "";
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to calculate hash", e);
            String fallback = content == null ? "" : content;
            return fallback.substring(0, Math.min(100, fallback.length()));
        }
    }

    private EvidenceEntity replaceExistingEvidence(EvidenceEntity existing, String contentText, Double confidence) {
        String sanitizedContent = evidenceService.sanitize(contentText);
        existing.setSource("rovo-incident-commander");
        existing.setContentText(sanitizedContent);
        existing.setContentHash(calculateContentHash(sanitizedContent));
        existing.setConfidence(confidence);
        existing.setSanitized(true);
        return evidenceRepository.save(existing);
    }

    private boolean isSameRovoRcaEvidence(EvidenceEntity evidence, String commentId, String rawRovoJsonText) {
        String content = evidence.getContentText();
        if (content == null || content.isBlank()) {
            return false;
        }

        try {
            RovoRcaEnvelope existing = objectMapper.readValue(content, RovoRcaEnvelope.class);
            if (commentId != null && commentId.equals(existing.commentId())) {
                return true;
            }
            if (existing.rawRovoJson() != null) {
                return existing.rawRovoJson().equals(objectMapper.readTree(rawRovoJsonText));
            }
        } catch (Exception ignored) {
            // Fall through to legacy JSON comparison.
        }

        try {
            return objectMapper.readTree(content).equals(objectMapper.readTree(rawRovoJsonText));
        } catch (Exception ignored) {
            return calculateHash(rawRovoJsonText).equals(calculateHash(content));
        }
    }

    private String calculateContentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record RovoRcaBlock(
            String commentId,
            String commentAuthor,
            String json,
            String rawHumanReport,
            String bodyFormat,
            int normalizedTextLength
    ) {}

    private com.fasterxml.jackson.databind.JsonNode normalizeRovoRcaJson(
            com.fasterxml.jackson.databind.JsonNode json,
            List<String> warnings
    ) {
        com.fasterxml.jackson.databind.node.ObjectNode normalized = json.deepCopy();

        // Normalize evidenceMatrix: object {loki, tempo, source} -> array [{category, status, references, reason}]
        if (normalized.has("evidenceMatrix")) {
            com.fasterxml.jackson.databind.JsonNode evidenceMatrix = normalized.get("evidenceMatrix");
            if (evidenceMatrix.isObject()) {
                // Convert object form to array form
                com.fasterxml.jackson.databind.node.ArrayNode matrixArray = objectMapper.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode matrixObj = (com.fasterxml.jackson.databind.node.ObjectNode) evidenceMatrix;
                
                Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = matrixObj.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                    String category = field.getKey();
                    com.fasterxml.jackson.databind.JsonNode item = field.getValue();
                    if (item.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode entry = item.deepCopy();
                        entry.put("category", category);
                        normalizeEvidenceReferences(entry, "evidenceMatrix." + category + ".references", warnings);
                        matrixArray.add(entry);
                    } else if (item.isTextual()) {
                        com.fasterxml.jackson.databind.node.ObjectNode entry = objectMapper.createObjectNode();
                        entry.put("category", category);
                        entry.put("status", "");
                        entry.set("references", objectMapper.createArrayNode());
                        entry.put("reason", item.asText());
                        matrixArray.add(entry);
                        warnings.add("Normalized evidenceMatrix." + category + " from string to object");
                    }
                }
                
                normalized.set("evidenceMatrix", matrixArray);
                warnings.add("Normalized evidenceMatrix from object to array");
            } else if (evidenceMatrix.isArray()) {
                // Already array, just normalize references within entries
                com.fasterxml.jackson.databind.node.ArrayNode matrixArray = (com.fasterxml.jackson.databind.node.ArrayNode) evidenceMatrix;
                for (int i = 0; i < matrixArray.size(); i++) {
                    com.fasterxml.jackson.databind.JsonNode entry = matrixArray.get(i);
                    if (entry.isObject()) {
                        normalizeEvidenceReferences(
                                (com.fasterxml.jackson.databind.node.ObjectNode) entry,
                                "evidenceMatrix[" + i + "].references",
                                warnings
                        );
                    }
                }
            }
        }

        // Normalize relatedJiraIssues: string array -> object array
        normalized = normalizeStringArrayToObjectArray(normalized, "relatedJiraIssues", "jiraKey", warnings);

        // Normalize similarIncidents: string array -> object array
        normalized = normalizeStringArrayToObjectArray(normalized, "similarIncidents", "jiraKey", warnings);

        // Normalize confluenceReferences: string array -> object array  
        if (normalized.has("confluenceReferences")) {
            com.fasterxml.jackson.databind.JsonNode confRefs = normalized.get("confluenceReferences");
            if (confRefs.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode normalizedRefs = objectMapper.createArrayNode();
                for (com.fasterxml.jackson.databind.JsonNode item : confRefs) {
                    if (item.isTextual()) {
                        com.fasterxml.jackson.databind.node.ObjectNode refObj = objectMapper.createObjectNode();
                        refObj.put("title", item.asText());
                        refObj.putNull("url");
                        refObj.put("reason", "");
                        normalizedRefs.add(refObj);
                        warnings.add("Normalized confluenceReferences from string to object");
                    } else {
                        normalizedRefs.add(item);
                    }
                }
                normalized.set("confluenceReferences", normalizedRefs);
            }
        }

        // Normalize failureChain to probableFailureChain
        if (normalized.has("failureChain") && !normalized.has("probableFailureChain")) {
            com.fasterxml.jackson.databind.JsonNode failureChain = normalized.get("failureChain");
            if (failureChain.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode probableChain = objectMapper.createArrayNode();
                int order = 1;
                for (com.fasterxml.jackson.databind.JsonNode item : failureChain) {
                    if (item.isTextual()) {
                        probableChain.add(createFailureChainItem(item.asText(), order++));
                    } else {
                        probableChain.add(item);
                    }
                }
                normalized.set("probableFailureChain", probableChain);
                normalized.remove("failureChain");
                warnings.add("Normalized failureChain to probableFailureChain");
            }
        }

        // Normalize string fields to arrays
        normalized = normalizeStringToArray(normalized, "regressionTestHypothesis", warnings);
        normalized = normalizeStringToArray(normalized, "minimumFixDirection", warnings);
        normalized = normalizeStringToArray(normalized, "missingEvidence", warnings);
        normalized = normalizeStringToArray(normalized, "supportingEvidenceReferences", warnings);
        normalized = normalizeStringToArray(normalized, "competingHypotheses", warnings);
        normalized = normalizeStringToArray(normalized, "warnings", warnings);

        // Add defaults
        if (!normalized.has("status") || normalized.get("status").isNull()) {
            normalized.put("status", "HYPOTHESIS");
            warnings.add("Defaulted status to HYPOTHESIS");
        }

        if (!normalized.has("confidence") || normalized.get("confidence").isNull()) {
            normalized.put("confidence", 0.0);
            warnings.add("Defaulted confidence to 0.0");
        } else {
            double conf = normalized.get("confidence").asDouble();
            if (conf < 0.0 || conf > 1.0) {
                double clamped = Math.max(0.0, Math.min(1.0, conf));
                normalized.put("confidence", clamped);
                warnings.add("Clamped confidence from " + conf + " to " + clamped);
            }
        }

        // Ensure arrays exist for optional fields
        ensureArrayField(normalized, "probableFailureChain");
        ensureArrayField(normalized, "evidenceMatrix");
        ensureArrayField(normalized, "similarIncidents");
        ensureArrayField(normalized, "relatedJiraIssues");
        ensureArrayField(normalized, "confluenceReferences");
        ensureArrayField(normalized, "supportingEvidenceReferences");
        ensureArrayField(normalized, "competingHypotheses");
        ensureArrayField(normalized, "suspectedFiles");
        ensureArrayField(normalized, "suspectedClasses");
        ensureArrayField(normalized, "suspectedMethods");
        ensureArrayField(normalized, "regressionTestHypothesis");
        ensureArrayField(normalized, "minimumFixDirection");
        ensureArrayField(normalized, "missingEvidence");
        ensureArrayField(normalized, "warnings");

        return normalized;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode normalizeStringArrayToObjectArray(
            com.fasterxml.jackson.databind.node.ObjectNode normalized,
            String fieldName,
            String keyField,
            List<String> warnings
    ) {
        if (normalized.has(fieldName)) {
            com.fasterxml.jackson.databind.JsonNode items = normalized.get(fieldName);
            if (items.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode normalizedItems = objectMapper.createArrayNode();
                for (com.fasterxml.jackson.databind.JsonNode item : items) {
                    if (item.isTextual()) {
                        com.fasterxml.jackson.databind.node.ObjectNode itemObj = objectMapper.createObjectNode();
                        itemObj.put(keyField, item.asText());
                        itemObj.put("reason", "");
                        normalizedItems.add(itemObj);
                        warnings.add("Normalized " + fieldName + " from string to object");
                    } else {
                        normalizedItems.add(item);
                    }
                }
                normalized.set(fieldName, normalizedItems);
            }
        }
        return normalized;
    }

    private void normalizeEvidenceReferences(
            com.fasterxml.jackson.databind.node.ObjectNode entry,
            String fieldPath,
            List<String> warnings
    ) {
        if (entry.has("references") && entry.get("references").isTextual()) {
            com.fasterxml.jackson.databind.node.ArrayNode refsArray = objectMapper.createArrayNode();
            refsArray.add(entry.get("references").asText());
            entry.set("references", refsArray);
            warnings.add("Normalized " + fieldPath + " from string to array");
        } else if (!entry.has("references") || entry.get("references").isNull()) {
            entry.set("references", objectMapper.createArrayNode());
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode normalizeStringToArray(
            com.fasterxml.jackson.databind.node.ObjectNode normalized,
            String fieldName,
            List<String> warnings
    ) {
        if (normalized.has(fieldName)) {
            com.fasterxml.jackson.databind.JsonNode field = normalized.get(fieldName);
            if (field.isTextual()) {
                com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
                array.add(field.asText());
                normalized.set(fieldName, array);
                warnings.add("Normalized " + fieldName + " from string to array");
            }
        }
        return normalized;
    }

    private void ensureArrayField(com.fasterxml.jackson.databind.node.ObjectNode normalized, String fieldName) {
        if (!normalized.has(fieldName) || normalized.get(fieldName).isNull()) {
            normalized.set(fieldName, objectMapper.createArrayNode());
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode createFailureChainItem(String text, int order) {
        String classification = "UNKNOWN";
        String statement = text;
        
        if (text.startsWith("FACT:")) {
            classification = "FACT";
            statement = text.substring(5).trim();
        } else if (text.startsWith("INFERENCE:")) {
            classification = "INFERENCE";
            statement = text.substring(10).trim();
        } else if (text.startsWith("UNKNOWN:")) {
            classification = "UNKNOWN";
            statement = text.substring(8).trim();
        }
        
        com.fasterxml.jackson.databind.node.ObjectNode chainItem = objectMapper.createObjectNode();
        chainItem.put("order", order);
        chainItem.put("classification", classification);
        chainItem.put("statement", statement);
        chainItem.putNull("service");
        chainItem.putNull("operation");
        chainItem.set("evidenceReferences", objectMapper.createArrayNode());
        return chainItem;
    }
}

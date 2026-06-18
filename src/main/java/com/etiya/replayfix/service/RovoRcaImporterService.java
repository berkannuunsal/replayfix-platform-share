package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.IntegrationModels;
import com.etiya.replayfix.model.RovoRcaAnalysis;
import com.etiya.replayfix.model.RovoRcaImportResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
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
            RCA_START_MARKER + "\\s*\\n(.+?)\\n" + RCA_END_MARKER,
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
            log.info("ROVO_IMPORT_START caseId={}", caseId);
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

            // Check for duplicate using content hash
            String contentHash = calculateHash(rovoRcaBlock.json);
            Optional<EvidenceEntity> existingEvidence = evidenceRepository
                    .findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA)
                    .stream()
                    .filter(e -> contentHash.equals(calculateHash(e.getContentText())))
                    .findFirst();

            if (existingEvidence.isPresent()) {
                log.info("ROVO_IMPORT_DUPLICATE caseId={} existingEvidenceId={}", caseId, existingEvidence.get().getId());
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        commentsScanned, pagesScanned, markerStartFoundCount, markerEndFoundCount,
                        candidateCommentIds, latestCommentCreatedAt, latestCommentAuthor,
                        detectedBodyFormats, normalizedTextLengths, importedCommentId, importedBodyFormat
                );
                return RovoRcaImportResponse.duplicate(caseId, jiraKey, existingEvidence.get().getId(), diagnostics);
            }

            // Persist as ROVO_RCA evidence
            EvidenceEntity evidence = evidenceService.save(
                    caseId,
                    EvidenceType.ROVO_RCA,
                    "rovo-incident-commander",
                    rovoRcaBlock.json,
                    false // No sanitization needed
            );

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
        List<String> formats = List.of("MANUAL");
        List<Integer> lengths = List.of(rawComment != null ? rawComment.length() : 0);
        
        try {
            ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

            String jiraKey = caseEntity.getJiraKey();

            // Extract Rovo RCA from raw comment
            String rovoRcaJson = extractRovoRcaJson(rawComment);

            if (rovoRcaJson == null) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, null
                );
                return RovoRcaImportResponse.notFound(caseId, jiraKey, diagnostics);
            }

            // Parse and validate
            RovoRcaAnalysis rovoRca;
            try {
                rovoRca = objectMapper.readValue(rovoRcaJson, RovoRcaAnalysis.class);
            } catch (Exception e) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, null
                );
                return RovoRcaImportResponse.invalidJson(caseId, jiraKey, e.getMessage(), diagnostics);
            }

            // Check for duplicate
            String contentHash = calculateHash(rovoRcaJson);
            Optional<EvidenceEntity> existingEvidence = evidenceRepository
                    .findByCaseIdAndEvidenceType(caseId, EvidenceType.ROVO_RCA)
                    .stream()
                    .filter(e -> contentHash.equals(calculateHash(e.getContentText())))
                    .findFirst();

            if (existingEvidence.isPresent()) {
                RovoRcaImportResponse.ImportDiagnostics diagnostics = new RovoRcaImportResponse.ImportDiagnostics(
                        0, 0, 0, 0, List.of(), null, null, formats, lengths, null, "MANUAL"
                );
                return RovoRcaImportResponse.duplicate(caseId, jiraKey, existingEvidence.get().getId(), diagnostics);
            }

            // Persist
            EvidenceEntity evidence = evidenceService.save(
                    caseId,
                    EvidenceType.ROVO_RCA,
                    "rovo-incident-commander",
                    rovoRcaJson,
                    false
            );

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
                    rovoRca.confidence(),
                    rovoRca.probableRootCause(),
                    diagnostics,
                    false,
                    List.of()
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
                int normalizedLength = body != null ? body.length() : 0;
                return new RovoRcaBlock(comment.id(), json, bodyFormat, normalizedLength);
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
            return matcher.group(1).trim();
        }

        return null;
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to calculate hash", e);
            return content.substring(0, Math.min(100, content.length()));
        }
    }

    private record RovoRcaBlock(String commentId, String json, String bodyFormat, int normalizedTextLength) {}

    private com.fasterxml.jackson.databind.JsonNode normalizeRovoRcaJson(
            com.fasterxml.jackson.databind.JsonNode json,
            List<String> warnings
    ) {
        com.fasterxml.jackson.databind.node.ObjectNode normalized = json.deepCopy();

        // Normalize relatedJiraIssues
        if (normalized.has("relatedJiraIssues")) {
            com.fasterxml.jackson.databind.JsonNode relatedIssues = normalized.get("relatedJiraIssues");
            if (relatedIssues.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode normalizedIssues = objectMapper.createArrayNode();
                for (com.fasterxml.jackson.databind.JsonNode item : relatedIssues) {
                    if (item.isTextual()) {
                        // String form: convert to object
                        com.fasterxml.jackson.databind.node.ObjectNode issueObj = objectMapper.createObjectNode();
                        issueObj.put("jiraKey", item.asText());
                        issueObj.put("reason", "");
                        normalizedIssues.add(issueObj);
                        warnings.add("Normalized relatedJiraIssues from string to object");
                    } else {
                        normalizedIssues.add(item);
                    }
                }
                normalized.set("relatedJiraIssues", normalizedIssues);
            }
        }

        // Normalize confluenceReferences
        if (normalized.has("confluenceReferences")) {
            com.fasterxml.jackson.databind.JsonNode confRefs = normalized.get("confluenceReferences");
            if (confRefs.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode normalizedRefs = objectMapper.createArrayNode();
                for (com.fasterxml.jackson.databind.JsonNode item : confRefs) {
                    if (item.isTextual()) {
                        // String form: convert to object
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

        // Normalize evidenceMatrix references
        if (normalized.has("evidenceMatrix")) {
            com.fasterxml.jackson.databind.JsonNode evidenceMatrix = normalized.get("evidenceMatrix");
            if (evidenceMatrix.isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode matrixObj = (com.fasterxml.jackson.databind.node.ObjectNode) evidenceMatrix;
                for (String key : List.of("loki", "tempo", "source")) {
                    if (matrixObj.has(key)) {
                        com.fasterxml.jackson.databind.JsonNode item = matrixObj.get(key);
                        if (item.isObject() && item.has("references")) {
                            com.fasterxml.jackson.databind.JsonNode refs = item.get("references");
                            if (refs.isTextual()) {
                                // String form: convert to array
                                com.fasterxml.jackson.databind.node.ArrayNode refsArray = objectMapper.createArrayNode();
                                refsArray.add(refs.asText());
                                ((com.fasterxml.jackson.databind.node.ObjectNode) item).set("references", refsArray);
                                warnings.add("Normalized evidenceMatrix." + key + ".references from string to array");
                            }
                        }
                    }
                }
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
                        String text = item.asText();
                        String classification = "UNKNOWN";
                        String statement = text;
                        
                        // Infer classification from prefix
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
                        chainItem.put("order", order++);
                        chainItem.put("classification", classification);
                        chainItem.put("statement", statement);
                        chainItem.putNull("service");
                        chainItem.putNull("operation");
                        chainItem.set("evidenceReferences", objectMapper.createArrayNode());
                        probableChain.add(chainItem);
                    } else {
                        probableChain.add(item);
                    }
                }
                normalized.set("probableFailureChain", probableChain);
                normalized.remove("failureChain");
                warnings.add("Normalized failureChain to probableFailureChain");
            }
        }

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

        return normalized;
    }
}

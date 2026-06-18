package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.JiraCommentPublishResponse;
import com.etiya.replayfix.model.ReplayFixEvidenceSnapshot;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RovoSnapshotPublisherService {

    private static final Logger log = LoggerFactory.getLogger(RovoSnapshotPublisherService.class);

    private static final String SNAPSHOT_START_MARKER = "REPLAYFIX_EVIDENCE_SNAPSHOT_V1";
    private static final String SNAPSHOT_END_MARKER = "REPLAYFIX_EVIDENCE_SNAPSHOT_END";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceSnapshotService snapshotService;
    private final JiraClient jiraClient;
    private final ObjectMapper objectMapper;

    public RovoSnapshotPublisherService(
            ReplayCaseRepository caseRepository,
            EvidenceSnapshotService snapshotService,
            JiraClient jiraClient,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.snapshotService = snapshotService;
        this.jiraClient = jiraClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> publishToJira(UUID caseId) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

            String jiraKey = caseEntity.getJiraKey();
            if (jiraKey == null || jiraKey.isBlank()) {
                throw new IllegalStateException("Case has no Jira key");
            }

            // Build snapshot
            ReplayFixEvidenceSnapshot snapshot = snapshotService.buildSnapshot(caseId);

            // Create ADF comment body
            JsonNode adfBody = buildAdfComment(snapshot);

            // Add comment to Jira issue using ADF
            JiraCommentPublishResponse response = jiraClient.addCommentAdf(jiraKey, adfBody);

            result.put("status", response.success() ? "SUCCESS" : "FAILED");
            result.put("caseId", caseId);
            result.put("jiraKey", jiraKey);
            result.put("snapshotVersion", snapshot.schemaVersion());
            result.put("commentId", response.commentId());
            result.put("warnings", response.warnings());

            if (response.success()) {
                log.info("Published evidence snapshot to Jira: caseId={}, jiraKey={}, commentId={}", 
                        caseId, jiraKey, response.commentId());
            } else {
                log.error("Failed to publish evidence snapshot: caseId={}, warnings={}", caseId, response.warnings());
            }

        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            log.error("Failed to publish evidence snapshot: caseId={}", caseId, e);
        }

        return result;
    }

    private JsonNode buildAdfComment(ReplayFixEvidenceSnapshot snapshot) throws Exception {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("version", 1);
        doc.put("type", "doc");

        ArrayNode content = doc.putArray("content");

        // Header
        content.add(heading(3, "ReplayFix Evidence Snapshot v" + snapshot.schemaVersion()));

        // Case section
        content.add(heading(4, "Vaka (Case)"));
        ArrayNode caseItems = objectMapper.createArrayNode();
        caseItems.add(bulletItem("Vaka ID: " + snapshot.caseId()));
        caseItems.add(bulletItem("Jira Key: " + snapshot.jiraKey()));
        caseItems.add(bulletItem("Hedef: " + snapshot.targetKey()));
        caseItems.add(bulletItem("Sentetik: " + (snapshot.synthetic() ? "Evet" : "Hayır")));
        content.add(bulletList(caseItems));

        // Repository section
        if (snapshot.repository() != null) {
            content.add(heading(4, "Repository"));
            ArrayNode repoItems = objectMapper.createArrayNode();
            repoItems.add(bulletItem("Proje: " + snapshot.repository().projectKey()));
            repoItems.add(bulletItem("Repository: " + snapshot.repository().repositorySlug()));
            repoItems.add(bulletItem("Branch: " + snapshot.repository().sourceBranch()));
            content.add(bulletList(repoItems));
        }

        // Jenkins section
        if (snapshot.jenkins() != null) {
            content.add(heading(4, "Jenkins / Incident Version"));
            ArrayNode jenkinsItems = objectMapper.createArrayNode();
            jenkinsItems.add(bulletItem("Job: " + snapshot.jenkins().jobName()));
            jenkinsItems.add(bulletItem("Build: #" + snapshot.jenkins().buildNumber()));
            jenkinsItems.add(bulletItem("Jenkins Commit: " + snapshot.jenkins().commitSha()));
            
            if (snapshot.incidentVersion() != null) {
                jenkinsItems.add(bulletItem("Checkout Commit: " + snapshot.incidentVersion().checkoutCommitSha()));
                jenkinsItems.add(bulletItem("Exact Match: " + (snapshot.incidentVersion().exactMatch() ? "Evet" : "Hayır")));
            }
            
            content.add(bulletList(jenkinsItems));
        }

        // Runtime Evidence Quality
        if (snapshot.runtimeEvidence() != null) {
            content.add(heading(4, "Runtime Evidence Kalitesi"));
            ArrayNode runtimeItems = objectMapper.createArrayNode();
            runtimeItems.add(bulletItem("Loki eşleşen satır: " + snapshot.runtimeEvidence().lokiMatchedRowCount()));
            runtimeItems.add(bulletItem("Loki başarısız sorgu: " + snapshot.runtimeEvidence().lokiFailedQueryCount()));
            runtimeItems.add(bulletItem("Tempo trace istenen: " + snapshot.runtimeEvidence().tempoRequestedTraceCount()));
            runtimeItems.add(bulletItem("Tempo trace bulunan: " + snapshot.runtimeEvidence().tempoFoundTraceCount()));
            if (!snapshot.runtimeEvidence().extractedTraceIds().isEmpty()) {
                runtimeItems.add(bulletItem("Trace ID'ler: " + 
                    String.join(", ", snapshot.runtimeEvidence().extractedTraceIds())));
            }
            content.add(bulletList(runtimeItems));
        }

        // Source Context
        if (snapshot.sourceContext() != null) {
            ArrayNode sourceItems = objectMapper.createArrayNode();
            sourceItems.add(bulletItem("Taranan dosya: " + snapshot.sourceContext().scannedFileCount()));
            sourceItems.add(bulletItem("Eşleşen dosya: " + snapshot.sourceContext().matchedFileCount()));
            content.add(bulletList(sourceItems));
        }

        // Deterministic RCA
        if (snapshot.deterministicRca() != null) {
            content.add(heading(4, "Deterministik RCA"));
            ArrayNode rcaItems = objectMapper.createArrayNode();
            rcaItems.add(bulletItem("Sınıflandırma: " + snapshot.deterministicRca().classification()));
            rcaItems.add(bulletItem("Güven: " + String.format("%.2f", snapshot.deterministicRca().confidence())));
            rcaItems.add(bulletItem("Olası sebep: " + snapshot.deterministicRca().probableCause()));
            
            if (snapshot.deterministicRca().missingEvidence() != null && 
                !snapshot.deterministicRca().missingEvidence().isEmpty()) {
                rcaItems.add(bulletItem("Eksik kanıt: " + 
                    String.join(", ", snapshot.deterministicRca().missingEvidence())));
            }
            
            if (snapshot.deterministicRca().recommendedActions() != null && 
                !snapshot.deterministicRca().recommendedActions().isEmpty()) {
                rcaItems.add(bulletItem("Önerilen aksiyonlar: " + 
                    String.join(", ", snapshot.deterministicRca().recommendedActions())));
            }
            content.add(bulletList(rcaItems));
        }

        // Guardrails
        content.add(heading(4, "Koruma Kuralları (Guardrails)"));
        ArrayNode guardrailItems = objectMapper.createArrayNode();
        guardrailItems.add(bulletItem("✓ Sadece kanıt tabanlı analiz"));
        guardrailItems.add(bulletItem("✓ Otomatik merge yok"));
        guardrailItems.add(bulletItem("✓ Production deployment yok"));
        guardrailItems.add(bulletItem("✓ İnsan onayı gerekli"));
        content.add(bulletList(guardrailItems));

        // Machine-readable block
        content.add(paragraph("─────────────────────────────────────"));
        
        String jsonSnapshot = objectMapper.writeValueAsString(snapshot);
        
        String machineReadableBlock = SNAPSHOT_START_MARKER + "\n" + 
                                      jsonSnapshot + "\n" + 
                                      SNAPSHOT_END_MARKER;
        
        content.add(codeBlock("json", machineReadableBlock));

        return doc;
    }

    private ObjectNode heading(int level, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "heading");
        node.put("attrs", objectMapper.createObjectNode().put("level", level));
        
        ArrayNode content = node.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        
        return node;
    }

    private ObjectNode paragraph(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "paragraph");
        
        ArrayNode content = node.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        
        return node;
    }

    private ObjectNode bulletList(ArrayNode items) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "bulletList");
        node.set("content", items);
        return node;
    }

    private ObjectNode bulletItem(String text) {
        ObjectNode listItem = objectMapper.createObjectNode();
        listItem.put("type", "listItem");
        
        ArrayNode listItemContent = listItem.putArray("content");
        ObjectNode para = listItemContent.addObject();
        para.put("type", "paragraph");
        
        ArrayNode paraContent = para.putArray("content");
        ObjectNode textNode = paraContent.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        
        return listItem;
    }

    private ObjectNode codeBlock(String language, String code) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "codeBlock");
        
        ObjectNode attrs = node.putObject("attrs");
        attrs.put("language", language);
        
        ArrayNode content = node.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", code);
        
        return node;
    }
}

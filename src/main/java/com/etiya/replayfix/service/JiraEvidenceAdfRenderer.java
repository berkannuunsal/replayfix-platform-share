package com.etiya.replayfix.service;

import com.etiya.replayfix.model.JiraEvidenceMatrixItem;
import com.etiya.replayfix.model.JiraEvidenceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JiraEvidenceAdfRenderer {

    private static final int MAX_COMMENT_CHARS = 25000;
    private final ObjectMapper objectMapper;

    public JiraEvidenceAdfRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode render(JiraEvidenceSnapshot snapshot) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);

        ArrayNode content = doc.putArray("content");

        addHeading(content, "ReplayFix Evidence Snapshot", 1);
        addMetadata(content, snapshot);
        addExecutiveBrief(content, snapshot);
        addEvidenceMatrix(content, snapshot.evidenceMatrix());
        addProbableFailureChain(content, snapshot.probableFailureChain());
        addRootCause(content, snapshot);
        addRegressionTestHypothesis(content, snapshot.regressionTestHypothesis());
        addMinimumFixDirection(content, snapshot.minimumFixDirection());
        addMissingEvidence(content, snapshot.missingEvidence());
        addRecommendedNextAction(content, snapshot);
        addFooter(content, snapshot);

        return doc;
    }

    private void addHeading(ArrayNode content, String text, int level) {
        ObjectNode heading = objectMapper.createObjectNode();
        heading.put("type", "heading");
        heading.put("attrs", objectMapper.createObjectNode().put("level", level));

        ArrayNode headingContent = heading.putArray("content");
        ObjectNode textNode = objectMapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", text);
        headingContent.add(textNode);

        content.add(heading);
    }

    private void addParagraph(ArrayNode content, String text) {
        if (text == null || text.isBlank()) return;

        ObjectNode para = objectMapper.createObjectNode();
        para.put("type", "paragraph");

        ArrayNode paraContent = para.putArray("content");
        ObjectNode textNode = objectMapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", text);
        paraContent.add(textNode);

        content.add(para);
    }

    private void addBulletList(ArrayNode content, List<String> items) {
        if (items == null || items.isEmpty()) return;

        ObjectNode bulletList = objectMapper.createObjectNode();
        bulletList.put("type", "bulletList");

        ArrayNode listContent = bulletList.putArray("content");

        for (String item : items) {
            if (item == null || item.isBlank()) continue;

            ObjectNode listItem = objectMapper.createObjectNode();
            listItem.put("type", "listItem");

            ArrayNode listItemContent = listItem.putArray("content");
            ObjectNode para = objectMapper.createObjectNode();
            para.put("type", "paragraph");

            ArrayNode paraContent = para.putArray("content");
            ObjectNode textNode = objectMapper.createObjectNode();
            textNode.put("type", "text");
            textNode.put("text", item);
            paraContent.add(textNode);

            listItemContent.add(para);
            listContent.add(listItem);
        }

        content.add(bulletList);
    }

    private void addMetadata(ArrayNode content, JiraEvidenceSnapshot snapshot) {
        addParagraph(content, "Analysis ID: " + snapshot.analysisId());
        addParagraph(content, "Workflow Status: " + snapshot.workflowStatus());
        addParagraph(content, "Generated: " + snapshot.generatedAt());
    }

    private void addExecutiveBrief(ArrayNode content, JiraEvidenceSnapshot snapshot) {
        addHeading(content, "Executive Incident Brief", 2);
        addParagraph(content, "Business Impact: " + snapshot.businessImpact());
        addParagraph(content, "Technical Symptom: " + snapshot.technicalSymptom());
        addParagraph(content, "Affected Flow: " + snapshot.affectedFlow());
    }

    private void addEvidenceMatrix(ArrayNode content, List<JiraEvidenceMatrixItem> matrix) {
        if (matrix == null || matrix.isEmpty()) return;

        addHeading(content, "Evidence Matrix", 2);

        for (JiraEvidenceMatrixItem item : matrix) {
            String summary = item.source() + " - " + item.status().name();
            addParagraph(content, summary);
            addParagraph(content, "Key Finding: " + item.keyFinding());
            addParagraph(content, "Confidence: " + item.confidence());
        }
    }

    private void addProbableFailureChain(ArrayNode content, List<String> chain) {
        addHeading(content, "Probable Failure Chain", 2);
        addBulletList(content, chain);
    }

    private void addRootCause(ArrayNode content, JiraEvidenceSnapshot snapshot) {
        addHeading(content, "Deterministic Root-Cause Hypothesis", 2);
        addParagraph(content, snapshot.probableRootCause());
        addParagraph(content, "Confidence: " + String.format("%.2f", snapshot.rootCauseConfidence()));

        if (snapshot.competingHypotheses() != null && !snapshot.competingHypotheses().isEmpty()) {
            addParagraph(content, "Competing Hypotheses:");
            addBulletList(content, snapshot.competingHypotheses());
        }
    }

    private void addRegressionTestHypothesis(ArrayNode content, List<String> hypothesis) {
        if (hypothesis == null || hypothesis.isEmpty()) return;

        addHeading(content, "Regression Test Hypothesis", 2);
        addBulletList(content, hypothesis);
    }

    private void addMinimumFixDirection(ArrayNode content, List<String> directions) {
        if (directions == null || directions.isEmpty()) return;

        addHeading(content, "Minimum Fix Direction", 2);
        addBulletList(content, directions);
    }

    private void addMissingEvidence(ArrayNode content, List<String> missing) {
        if (missing == null || missing.isEmpty()) return;

        addHeading(content, "Missing Evidence", 2);
        addBulletList(content, missing);
    }

    private void addRecommendedNextAction(ArrayNode content, JiraEvidenceSnapshot snapshot) {
        addHeading(content, "Recommended Next Action", 2);
        addParagraph(content, snapshot.recommendedNextAction());
    }

    private void addFooter(ArrayNode content, JiraEvidenceSnapshot snapshot) {
        if (snapshot.warnings() != null && !snapshot.warnings().isEmpty()) {
            addHeading(content, "Warnings", 3);
            addBulletList(content, snapshot.warnings());
        }

        addParagraph(content, "---");
        addParagraph(content, "This evidence snapshot was generated by ReplayFix.");
        addParagraph(content, "Analysis ID: " + snapshot.analysisId());
    }

    public String renderPlainText(JiraEvidenceSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== ReplayFix Evidence Snapshot ===\n\n");
        sb.append("Analysis ID: ").append(snapshot.analysisId()).append("\n");
        sb.append("Workflow Status: ").append(snapshot.workflowStatus()).append("\n");
        sb.append("Generated: ").append(snapshot.generatedAt()).append("\n\n");

        sb.append("== Executive Incident Brief ==\n");
        sb.append("Business Impact: ").append(snapshot.businessImpact()).append("\n");
        sb.append("Technical Symptom: ").append(snapshot.technicalSymptom()).append("\n");
        sb.append("Affected Flow: ").append(snapshot.affectedFlow()).append("\n\n");

        sb.append("== Evidence Matrix ==\n");
        if (snapshot.evidenceMatrix() != null) {
            for (JiraEvidenceMatrixItem item : snapshot.evidenceMatrix()) {
                sb.append("- ").append(item.source()).append(" (").append(item.status()).append(")\n");
                sb.append("  Finding: ").append(item.keyFinding()).append("\n");
                sb.append("  Confidence: ").append(item.confidence()).append("\n");
            }
        }
        sb.append("\n");

        sb.append("== Probable Failure Chain ==\n");
        if (snapshot.probableFailureChain() != null) {
            for (String step : snapshot.probableFailureChain()) {
                sb.append("- ").append(step).append("\n");
            }
        }
        sb.append("\n");

        sb.append("== Root-Cause Hypothesis ==\n");
        sb.append(snapshot.probableRootCause()).append("\n");
        sb.append("Confidence: ").append(String.format("%.2f", snapshot.rootCauseConfidence())).append("\n\n");

        if (snapshot.regressionTestHypothesis() != null && !snapshot.regressionTestHypothesis().isEmpty()) {
            sb.append("== Regression Test Hypothesis ==\n");
            for (String test : snapshot.regressionTestHypothesis()) {
                sb.append("- ").append(test).append("\n");
            }
            sb.append("\n");
        }

        if (snapshot.missingEvidence() != null && !snapshot.missingEvidence().isEmpty()) {
            sb.append("== Missing Evidence ==\n");
            for (String missing : snapshot.missingEvidence()) {
                sb.append("- ").append(missing).append("\n");
            }
            sb.append("\n");
        }

        sb.append("== Recommended Next Action ==\n");
        sb.append(snapshot.recommendedNextAction()).append("\n\n");

        sb.append("---\n");
        sb.append("Generated by ReplayFix | Analysis ID: ").append(snapshot.analysisId()).append("\n");

        String result = sb.toString();
        if (result.length() > MAX_COMMENT_CHARS) {
            return result.substring(0, MAX_COMMENT_CHARS - 100) + "\n\n[Truncated due to size limit]";
        }

        return result;
    }
}

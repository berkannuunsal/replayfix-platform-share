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

        addHeading(content, "ReplayLab Evidence Snapshot", 1);
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
        addParagraph(content, "This evidence snapshot was generated by ReplayLab.");
        addParagraph(content, "Analysis ID: " + snapshot.analysisId());
    }

    public String renderPlainText(JiraEvidenceSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        sb.append("ReplayLab Evidence Snapshot\n\n");

        sb.append("Executive Incident Brief\n\n");
        sb.append("* Jira: ").append(snapshot.issueKey()).append("\n");
        sb.append("* Target: ").append(snapshot.businessImpact()).append("\n");
        appendChainValue(sb, snapshot, "Repository");
        appendChainValue(sb, snapshot, "Branch");
        appendChainValue(sb, snapshot, "Jenkins Build");
        appendChainValue(sb, snapshot, "Incident Version");
        appendChainValue(sb, snapshot, "RCA Status");
        sb.append("\n");

        sb.append("Evidence Quality\n\n");
        appendEvidenceQuality(sb, snapshot, "JIRA", "Jira");
        appendEvidenceQuality(sb, snapshot, "BITBUCKET", "Bitbucket");
        appendEvidenceQuality(sb, snapshot, "JENKINS", "Jenkins");
        appendEvidenceQuality(sb, snapshot, "LOKI", "Loki");
        appendEvidenceQuality(sb, snapshot, "TEMPO", "Tempo");
        appendEvidenceQuality(sb, snapshot, "SOURCE_CONTEXT", "Source Context");
        appendEvidenceQuality(sb, snapshot, "REPLAYFIX", "ReplayLab");
        sb.append("\n");

        sb.append("Root-Cause Hypothesis\n\n");
        sb.append("* ").append(snapshot.probableRootCause()).append("\n");
        sb.append("* Confidence: ")
                .append(String.format("%.0f%%", snapshot.rootCauseConfidence() * 100))
                .append("\n\n");

        if (snapshot.regressionTestHypothesis() != null && !snapshot.regressionTestHypothesis().isEmpty()) {
            sb.append("Regression Test Hypothesis\n\n");
            for (String test : snapshot.regressionTestHypothesis()) {
                sb.append("* ").append(test).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Recommended Next Action\n\n");
        sb.append(snapshot.recommendedNextAction()).append("\n\n");

        String result = sb.toString();
        if (result.length() > MAX_COMMENT_CHARS) {
            return result.substring(0, MAX_COMMENT_CHARS - 100) + "\n\n[Truncated due to size limit]";
        }

        return result;
    }

    private void appendChainValue(
            StringBuilder sb,
            JiraEvidenceSnapshot snapshot,
            String label
    ) {
        if (snapshot.probableFailureChain() == null) {
            return;
        }

        String prefix = label + ":";
        snapshot.probableFailureChain()
                .stream()
                .filter(item -> item != null && item.startsWith(prefix))
                .findFirst()
                .ifPresent(value -> sb.append("* ")
                        .append(label)
                        .append(": ")
                        .append(value.substring(prefix.length()).trim())
                        .append("\n"));
    }

    private void appendEvidenceQuality(
            StringBuilder sb,
            JiraEvidenceSnapshot snapshot,
            String source,
            String label
    ) {
        if (snapshot.evidenceMatrix() == null) {
            return;
        }

        snapshot.evidenceMatrix()
                .stream()
                .filter(item -> source.equals(item.source()))
                .findFirst()
                .ifPresent(item -> {
                    sb.append("* ")
                            .append(label)
                            .append(": ")
                            .append(item.status().name());
                    if (item.keyFinding() != null
                            && !item.keyFinding().isBlank()
                            && item.status().name().equals("PROBABLE")) {
                        sb.append(" - ").append(item.keyFinding());
                    }
                    sb.append("\n");
                });
    }
}

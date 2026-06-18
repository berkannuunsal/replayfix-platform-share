package com.etiya.replayfix.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class JiraAdfTextExtractor {

    private final ObjectMapper objectMapper;

    public JiraAdfTextExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extract(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }

        try {
            JsonNode root = objectMapper.readTree(description);

            StringBuilder result = new StringBuilder();
            appendText(root, result);

            return result.toString()
                    .replaceAll("[ \\t]+", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        } catch (Exception exception) {
            return description;
        }
    }

    private void appendText(JsonNode node, StringBuilder result) {
        if (node == null) {
            return;
        }

        String type = node.path("type").asText("");

        switch (type) {
            case "text" -> result.append(node.path("text").asText(""));
            case "hardBreak", "rule" -> result.append('\n');
            default -> {
            }
        }

        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                appendText(child, result);
            }
        }

        if (isBlockNode(type)) {
            result.append('\n');
        }
    }

    private boolean isBlockNode(String type) {
        return "paragraph".equals(type)
                || "heading".equals(type)
                || "listItem".equals(type)
                || "codeBlock".equals(type)
                || "panel".equals(type)
                || "blockquote".equals(type)
                || "bulletList".equals(type)
                || "orderedList".equals(type);
    }
}

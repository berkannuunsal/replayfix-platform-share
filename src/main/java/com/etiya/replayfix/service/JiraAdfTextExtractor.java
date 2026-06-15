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
            // Jira description düz metinse olduğu gibi kullan.
            return description;
        }
    }

    private void appendText(JsonNode node, StringBuilder result) {
        if (node == null) {
            return;
        }

        String type = node.path("type").asText("");

        if ("text".equals(type)) {
            result.append(node.path("text").asText(""));
        }

        if ("hardBreak".equals(type)) {
            result.append('\n');
        }

        JsonNode content = node.path("content");

        if (content.isArray()) {
            for (JsonNode child : content) {
                appendText(child, result);
            }
        }

        if ("paragraph".equals(type)
            || "listItem".equals(type)
            || "heading".equals(type)) {
            result.append('\n');
        }
    }
}

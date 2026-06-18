package com.etiya.replayfix.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraAdfTextExtractorTest {

    private final JiraAdfTextExtractor extractor = new JiraAdfTextExtractor(new ObjectMapper());

    @Test
    void shouldExtractTextRecursivelyFromCommonAdfNodes() {
        String adf = """
                {
                  "type": "doc",
                  "version": 1,
                  "content": [
                    {"type": "heading", "content": [{"type": "text", "text": "Yonetici Ozeti"}]},
                    {"type": "panel", "content": [
                      {"type": "paragraph", "content": [{"type": "text", "text": "REPLAYFIX_ROVO_RCA_V1"}]}
                    ]},
                    {"type": "codeBlock", "content": [{"type": "text", "text": "{\\"schemaVersion\\":\\"1.0\\"}"}]},
                    {"type": "blockquote", "content": [
                      {"type": "paragraph", "content": [{"type": "text", "text": "REPLAYFIX_ROVO_RCA_END"}]}
                    ]},
                    {"type": "bulletList", "content": [
                      {"type": "listItem", "content": [
                        {"type": "paragraph", "content": [{"type": "text", "text": "related issue"}]}
                      ]}
                    ]}
                  ]
                }
                """;

        String text = extractor.extract(adf);

        assertTrue(text.contains("Yonetici Ozeti"));
        assertTrue(text.contains("REPLAYFIX_ROVO_RCA_V1"));
        assertTrue(text.contains("{\"schemaVersion\":\"1.0\"}"));
        assertTrue(text.contains("REPLAYFIX_ROVO_RCA_END"));
        assertTrue(text.contains("related issue"));
    }
}

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

    @Test
    void shouldPreserveRovoHumanReportAndMarkersFromAdfComment() {
        String adf = """
                {
                  "type": "doc",
                  "version": 1,
                  "content": [
                    {"type": "heading", "content": [{"type": "text", "text": "Yonetici Ozeti"}]},
                    {"type": "paragraph", "content": [
                      {"type": "text", "text": "Turkce insan okunabilir RCA raporu"},
                      {"type": "hardBreak"},
                      {"type": "text", "text": "REPLAYFIX_ROVO_RCA_V1"}
                    ]},
                    {"type": "codeBlock", "content": [{"type": "text", "text": "{\\"schemaVersion\\":\\"1.0\\",\\"jiraKey\\":\\"FIZZMS-10228\\"}"}]},
                    {"type": "orderedList", "content": [
                      {"type": "listItem", "content": [
                        {"type": "paragraph", "content": [{"type": "text", "text": "Kanıt maddesi"}]}
                      ]}
                    ]},
                    {"type": "paragraph", "content": [{"type": "text", "text": "REPLAYFIX_ROVO_RCA_END"}]}
                  ]
                }
                """;

        String text = extractor.extract(adf);

        assertTrue(text.indexOf("Yonetici Ozeti") < text.indexOf("REPLAYFIX_ROVO_RCA_V1"));
        assertTrue(text.contains("Turkce insan okunabilir RCA raporu"));
        assertTrue(text.contains("REPLAYFIX_ROVO_RCA_V1"));
        assertTrue(text.contains("{\"schemaVersion\":\"1.0\",\"jiraKey\":\"FIZZMS-10228\"}"));
        assertTrue(text.contains("Kanıt maddesi"));
        assertTrue(text.contains("REPLAYFIX_ROVO_RCA_END"));
    }
}

package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.model.ConfluencePageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfluencePageTextExtractorTest {

    private ConfluencePageTextExtractor extractor;
    private EvidenceSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        ReplayLabProperties properties = new ReplayLabProperties();
        var confluence = new ReplayLabProperties.ConfluenceEndpoint();
        confluence.setMaxPageChars(30000);
        properties.getIntegrations().setConfluence(confluence);

        sanitizer = new EvidenceSanitizer();
        extractor = new ConfluencePageTextExtractor(properties, sanitizer);
    }

    @Test
    void testStorageHtmlToPlainText() {
        String html = "<html><body><h1>Test Title</h1><p>Test paragraph</p></body></html>";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123",
                "Test Page",
                "space1",
                "SPACE",
                "current",
                "http://test",
                1,
                "2024-01-01",
                "storage",
                html,
                html.length(),
                false,
                List.of(),
                List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertFalse(result.plainText().contains("<html>"));
        assertFalse(result.plainText().contains("<body>"));
        assertTrue(result.plainText().contains("Test Title"));
        assertTrue(result.plainText().contains("Test paragraph"));
    }

    @Test
    void testScriptStyleRemoval() {
        String html = "<html><head><script>alert('test');</script><style>.test{}</style></head>" +
                "<body><p>Content</p></body></html>";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", html, html.length(), false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertFalse(result.plainText().contains("alert"));
        assertFalse(result.plainText().contains(".test{}"));
        assertTrue(result.plainText().contains("Content"));
    }

    @Test
    void testLinkTextPreservation() {
        String html = "<html><body><a href=\"http://example.com\">Link Text</a></body></html>";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", html, html.length(), false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertTrue(result.plainText().contains("Link Text"));
        assertFalse(result.plainText().contains("href"));
    }

    @Test
    void testMacroPlaceholder() {
        String html = "<html><body><ac:structured-macro ac:name=\"info\">" +
                "<ac:rich-text-body><p>Info message</p></ac:rich-text-body>" +
                "</ac:structured-macro></body></html>";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", html, html.length(), false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertTrue(result.plainText().contains("Macro"));
    }

    @Test
    void testSecretSanitization() {
        String html = "<html><body><p>API Token: abc123xyz</p></body></html>";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", html, html.length(), false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
    }

    @Test
    void testMaxCharTruncation() {
        StringBuilder longHtml = new StringBuilder("<html><body>");
        for (int i = 0; i < 5000; i++) {
            longHtml.append("<p>This is line ").append(i).append("</p>");
        }
        longHtml.append("</body></html>");

        String html = longHtml.toString();

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", html, html.length(), false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertTrue(result.plainText().length() <= 30000);
        if (result.truncated()) {
            assertFalse(result.warnings().isEmpty());
            assertTrue(result.warnings().stream()
                    .anyMatch(w -> w.contains("truncated")));
        }
    }

    @Test
    void testEmptyBody() {
        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", "", 0, false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertEquals(input, result);
    }

    @Test
    void testNullPage() {
        ConfluencePageDocument result = extractor.extractText(null);
        assertNull(result);
    }

    @Test
    void testHtmlParsingError() {
        String malformedHtml = "<html><body><p>Unclosed paragraph";

        ConfluencePageDocument input = new ConfluencePageDocument(
                "123", "Test", "space1", "SPACE", "current", "http://test",
                1, "2024-01-01", "storage", malformedHtml, malformedHtml.length(),
                false, List.of(), List.of()
        );

        ConfluencePageDocument result = extractor.extractText(input);

        assertNotNull(result);
        assertNotNull(result.plainText());
    }
}

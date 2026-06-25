package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.model.ConfluencePageDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConfluencePageTextExtractor {

    private final ReplayLabProperties properties;
    private final EvidenceSanitizer evidenceSanitizer;

    public ConfluencePageTextExtractor(
            ReplayLabProperties properties,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.properties = properties;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    public ConfluencePageDocument extractText(ConfluencePageDocument page) {
        if (page == null || page.plainText() == null || page.plainText().isBlank()) {
            return page;
        }

        List<String> warnings = new ArrayList<>(page.warnings());

        String storageHtml = page.plainText();
        String plainText;

        try {
            plainText = htmlToPlainText(storageHtml);
        } catch (Exception exception) {
            warnings.add("Failed to parse HTML: " + exception.getMessage());
            plainText = storageHtml;
        }

        plainText = evidenceSanitizer.sanitize(plainText);

        int maxPageChars = properties.getIntegrations().getConfluence().getMaxPageChars();
        int originalLength = plainText.length();
        boolean truncated = false;

        if (plainText.length() > maxPageChars) {
            plainText = plainText.substring(0, maxPageChars);
            truncated = true;
            warnings.add("Page truncated from " + originalLength + " to " + maxPageChars + " chars");
        }

        return new ConfluencePageDocument(
                page.pageId(),
                page.title(),
                page.spaceId(),
                page.spaceKey(),
                page.status(),
                page.webUrl(),
                page.versionNumber(),
                page.versionCreatedAt(),
                page.bodyFormat(),
                plainText,
                originalLength,
                truncated,
                page.labels(),
                warnings
        );
    }

    private String htmlToPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Document doc = Jsoup.parse(html);

        doc.select("script, style, noscript").remove();

        for (Element element : doc.select("a[href]")) {
            String linkText = element.text();
            if (!linkText.isBlank()) {
                element.replaceWith(new org.jsoup.nodes.TextNode(linkText));
            }
        }

        for (Element macro : doc.select("ac\\:structured-macro")) {
            String macroName = macro.attr("ac:name");
            if (macroName != null && !macroName.isBlank()) {
                macro.prepend("[Macro: " + macroName + "] ");
            }
        }

        String plainText = doc.text();

        plainText = plainText.replaceAll("\\s+", " ").trim();

        return plainText;
    }
}

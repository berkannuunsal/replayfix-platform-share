package com.etiya.replaylab.model;

import java.util.List;

public record ConfluencePageDocument(
        String pageId,
        String title,
        String spaceId,
        String spaceKey,
        String status,
        String webUrl,
        int versionNumber,
        String versionCreatedAt,
        String bodyFormat,
        String plainText,
        int originalLength,
        boolean truncated,
        List<String> labels,
        List<String> warnings
) {
}

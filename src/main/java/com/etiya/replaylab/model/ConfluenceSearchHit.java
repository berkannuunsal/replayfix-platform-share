package com.etiya.replaylab.model;

import java.util.List;

public record ConfluenceSearchHit(
        String pageId,
        String title,
        String spaceKey,
        String spaceName,
        String url,
        String excerpt,
        String lastModified,
        int versionNumber,
        double apiScore,
        int replayLabScore,
        List<String> matchReasons
) {
}

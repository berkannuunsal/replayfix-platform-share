package com.etiya.replayfix.model;

import java.util.List;

public record ConfluenceSearchResponse(
        String cql,
        int resultCount,
        List<ConfluenceSearchHit> results,
        String nextCursor,
        List<String> warnings
) {
}

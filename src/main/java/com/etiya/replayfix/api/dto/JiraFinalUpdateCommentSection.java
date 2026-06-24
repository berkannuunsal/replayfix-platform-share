package com.etiya.replayfix.api.dto;

import java.util.List;

public record JiraFinalUpdateCommentSection(
        String title,
        String body,
        List<String> bullets
) {
    public JiraFinalUpdateCommentSection {
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
    }
}

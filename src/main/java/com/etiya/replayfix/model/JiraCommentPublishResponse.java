package com.etiya.replayfix.model;

import java.util.List;

public record JiraCommentPublishResponse(
        boolean success,
        Integer httpStatus,
        String issueKey,
        String commentId,
        String selfUrl,
        String createdAt,
        List<String> warnings
) {
}

package com.etiya.replayfix.model;

import java.util.List;

public record SourceRecentCommit(
        String commitSha,
        String shortSha,
        String author,
        String date,
        String message,
        List<String> jiraKeys,
        List<String> changedFiles,
        boolean touchedCandidateFile
) {
}

package com.etiya.replaylab.model;

public record SourceLastCommitDiagnostic(
        String file,
        String commitSha,
        String shortSha,
        String author,
        String date,
        String message
) {
    public SourceLastCommitDiagnostic {
        file = file == null ? "" : file;
        commitSha = commitSha == null ? "" : commitSha;
        shortSha = shortSha == null ? "" : shortSha;
        author = author == null ? "" : author;
        date = date == null ? "" : date;
        message = message == null ? "" : message;
    }
}

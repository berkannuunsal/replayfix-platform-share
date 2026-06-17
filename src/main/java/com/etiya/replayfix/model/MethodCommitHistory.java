package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;

/**
 * Method-level commit history from Bitbucket.
 * Tracks commits that actually touched specific methods in the last 45 days.
 */
public record MethodCommitHistory(
        String repository,
        String branch,
        String filePath,
        String className,
        String methodName,
        int lookbackDays,
        List<MethodCommit> commits,
        Instant analysisTimestamp
) {
    public record MethodCommit(
            String commitSha,
            String author,
            Instant timestamp,
            String commitMessage,
            String relatedJiraKey,
            String relatedPullRequest,
            String affectedFile,
            String affectedClass,
            String affectedMethod,
            ChangedLinesSummary changedLines,
            boolean directMethodChange,
            double riskScore,
            boolean beforeIncident,
            Instant incidentTime
    ) {}

    public record ChangedLinesSummary(
            int addedLines,
            int deletedLines,
            int modifiedLines,
            List<Integer> affectedLineNumbers
    ) {}
}

package com.etiya.replayfix.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class IntegrationModels {
    private IntegrationModels() {}

    public record JiraIssue(String key, String summary, String description, Map<String, Object> fields) {}
    public record JiraComment(String id, String author, Instant created, String body) {}
    public record LokiLogEntry(Instant timestamp, String labels, String line) {}
    public record TempoTrace(String traceId, String rawJson) {}
    public record KnowledgeResult(String source, String title, String content, String url) {}
    public record RootCauseResult(String summary, String probableRootCause, double confidence,
                                  List<String> evidence, List<String> remediationActions, String rawJson) {}
    public record ProvisionResult(String namespace, String applicationService, String databaseService, String manifestPath) {}
    public record ReplayResult(boolean reproduced, int actualStatus, String responseBody,
                               String applicationLogs, String failureSignature) {}
    public record GeneratedFile(String path, String content) {}
    public record GenerationResult(List<GeneratedFile> files, String explanation, String rawJson) {}
    public record BuildResult(String status, String buildUrl, int totalTests, int failedTests, String rawJson) {}
    public record GitPublishResult(String branch, String commitSha, Path workspace) {}
    public record PullRequestResult(String id, String url, String title) {}
}

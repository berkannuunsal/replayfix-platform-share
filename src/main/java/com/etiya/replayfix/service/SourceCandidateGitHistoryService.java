package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.SourceDiffSnippet;
import com.etiya.replayfix.model.SourceLastCommitDiagnostic;
import com.etiya.replayfix.model.SourceRecentCommit;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SourceCandidateGitHistoryService {

    public static final String METHOD_NOT_RESOLVED = "METHOD_NOT_RESOLVED";

    private static final int MAX_DIFF_CHARS = 4_000;
    private static final Pattern JIRA_KEY_PATTERN =
            Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");
    private static final Pattern HUNK_PATTERN =
            Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");

    private final ReplayFixProperties properties;
    private final EvidenceSanitizer evidenceSanitizer;

    public SourceCandidateGitHistoryService(
            ReplayFixProperties properties,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.properties = properties;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    public HistoryResult collect(
            Path workspace,
            List<String> candidateFiles,
            Map<String, FlowAwareSourceDiscoveryService.JavaFileInfo> javaFiles,
            int lookbackDays,
            int maxCommitsPerFile,
            boolean includeDiffSnippets
    ) {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return new HistoryResult(List.of(), List.of(), List.of("SOURCE_CHECKOUT_MAY_BE_INCOMPLETE"));
        }

        Map<String, SourceRecentCommit> commits = new LinkedHashMap<>();
        List<SourceDiffSnippet> snippets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<SourceLastCommitDiagnostic> lastCommitDiagnostics = new ArrayList<>();

        for (String file : candidateFiles) {
            List<SourceRecentCommit> fileCommits =
                    gitLog(workspace, file, lookbackDays, maxCommitsPerFile);
            fileCommits.forEach(commit ->
                    commits.putIfAbsent(commit.commitSha() + "#" + file, commit)
            );
            lastCommit(workspace, file).ifPresent(lastCommitDiagnostics::add);

            if (includeDiffSnippets) {
                for (SourceRecentCommit commit : fileCommits) {
                    SourceDiffSnippet snippet =
                            diffSnippet(workspace, file, commit.commitSha(), javaFiles);
                    snippets.add(snippet);
                    warnings.addAll(snippet.warnings());
                }
            }
        }

        return new HistoryResult(
                commits.values().stream().toList(),
                snippets,
                List.copyOf(new LinkedHashSet<>(warnings)),
                List.copyOf(lastCommitDiagnostics)
        );
    }

    private List<SourceRecentCommit> gitLog(
            Path workspace,
            String file,
            int lookbackDays,
            int maxCommitsPerFile
    ) {
        String output = run(
                workspace,
                List.of(
                        gitExecutable(),
                        "log",
                        "--since=" + Math.max(1, lookbackDays) + " days ago",
                        "--date=iso-strict",
                        "--pretty=format:--COMMIT--%H|%an|%ad|%s",
                        "--name-status",
                        "--",
                        file
                )
        );

        List<SourceRecentCommit> commits = new ArrayList<>();
        String currentSha = null;
        String currentAuthor = null;
        String currentDate = null;
        String currentMessage = null;
        Set<String> changedFiles = new LinkedHashSet<>();

        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("--COMMIT--")) {
                if (currentSha != null) {
                    commits.add(commit(
                            currentSha,
                            currentAuthor,
                            currentDate,
                            currentMessage,
                            changedFiles,
                            file
                    ));
                    if (commits.size() >= maxCommitsPerFile) {
                        return commits;
                    }
                }
                String[] parts = line.substring("--COMMIT--".length())
                        .split("\\|", 4);
                currentSha = parts.length > 0 ? parts[0] : "";
                currentAuthor = parts.length > 1 ? parts[1] : "";
                currentDate = parts.length > 2 ? parts[2] : "";
                currentMessage = parts.length > 3 ? parts[3] : "";
                changedFiles = new LinkedHashSet<>();
                continue;
            }

            if (line.matches("^[AMDRCT]\\s+.+$")) {
                changedFiles.add(line.substring(1).trim());
            }
        }

        if (currentSha != null && commits.size() < maxCommitsPerFile) {
            commits.add(commit(
                    currentSha,
                    currentAuthor,
                    currentDate,
                    currentMessage,
                    changedFiles,
                    file
            ));
        }

        return commits;
    }

    private Optional<SourceLastCommitDiagnostic> lastCommit(
            Path workspace,
            String file
    ) {
        String output = run(
                workspace,
                List.of(
                        gitExecutable(),
                        "log",
                        "-1",
                        "--date=iso-strict",
                        "--pretty=format:%H|%an|%ad|%s",
                        "--",
                        file
                )
        ).trim();
        if (output.isBlank()) {
            return Optional.empty();
        }
        String[] parts = output.split("\\|", 4);
        String sha = parts.length > 0 ? parts[0] : "";
        return Optional.of(new SourceLastCommitDiagnostic(
                file,
                sha,
                sha.length() <= 7 ? sha : sha.substring(0, 7),
                evidenceSanitizer.sanitize(parts.length > 1 ? parts[1] : ""),
                evidenceSanitizer.sanitize(parts.length > 2 ? parts[2] : ""),
                evidenceSanitizer.sanitize(parts.length > 3 ? parts[3] : "")
        ));
    }

    private SourceRecentCommit commit(
            String sha,
            String author,
            String date,
            String message,
            Set<String> changedFiles,
            String candidateFile
    ) {
        return new SourceRecentCommit(
                sha,
                sha.length() <= 7 ? sha : sha.substring(0, 7),
                evidenceSanitizer.sanitize(author),
                date,
                evidenceSanitizer.sanitize(message),
                jiraKeys(message),
                changedFiles.stream().toList(),
                changedFiles.contains(candidateFile)
        );
    }

    private SourceDiffSnippet diffSnippet(
            Path workspace,
            String file,
            String commitSha,
            Map<String, FlowAwareSourceDiscoveryService.JavaFileInfo> javaFiles
    ) {
        String diff = run(
                workspace,
                List.of(
                        gitExecutable(),
                        "show",
                        "--format=",
                        "--unified=20",
                        commitSha,
                        "--",
                        file
                )
        );
        diff = evidenceSanitizer.sanitize(diff);
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS);
        }

        List<String> warnings = new ArrayList<>();
        String methodName = mapDiffToMethod(diff, javaFiles.get(file))
                .orElse(null);
        if (file.endsWith(".java") && methodName == null) {
            warnings.add(METHOD_NOT_RESOLVED);
        }

        return new SourceDiffSnippet(
                commitSha,
                file,
                methodName,
                diff,
                warnings
        );
    }

    public Optional<String> mapDiffToMethod(
            String diff,
            FlowAwareSourceDiscoveryService.JavaFileInfo javaFileInfo
    ) {
        if (javaFileInfo == null) {
            return Optional.empty();
        }

        for (String line : diff.split("\\R")) {
            Matcher matcher = HUNK_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int start = Integer.parseInt(matcher.group(1));
            int length = matcher.group(2) == null
                    ? 1
                    : Integer.parseInt(matcher.group(2));
            int end = start + Math.max(0, length - 1);
            for (FlowAwareSourceDiscoveryService.JavaMethodInfo method
                    : javaFileInfo.methods()) {
                if (rangesOverlap(start, end, method.startLine(), method.endLine())) {
                    return Optional.of(method.name());
                }
            }
        }
        return Optional.empty();
    }

    private boolean rangesOverlap(
            int leftStart,
            int leftEnd,
            int rightStart,
            int rightEnd
    ) {
        return leftStart <= rightEnd && rightStart <= leftEnd;
    }

    private List<String> jiraKeys(String message) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = JIRA_KEY_PATTERN.matcher(message == null ? "" : message);
        while (matcher.find()) {
            keys.add(matcher.group());
        }
        return keys;
    }

    private String run(Path workspace, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return "";
            }
            return output;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String gitExecutable() {
        String value = properties.getIntegrations()
                .getBitbucket()
                .getGitExecutable();
        return value == null || value.isBlank() ? "git" : value;
    }

    public record HistoryResult(
            List<SourceRecentCommit> recentCommits,
            List<SourceDiffSnippet> diffSnippets,
            List<String> warnings,
            List<SourceLastCommitDiagnostic> lastCommitDiagnostics
    ) {
        public HistoryResult(
                List<SourceRecentCommit> recentCommits,
                List<SourceDiffSnippet> diffSnippets,
                List<String> warnings
        ) {
            this(recentCommits, diffSnippets, warnings, List.of());
        }
    }
}

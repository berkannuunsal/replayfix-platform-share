package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties.Target;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.AiEvidenceBundle.SourceExcerpt;
import com.etiya.replaylab.model.IncidentSignals;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IncidentTimelineEvent;
import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import com.etiya.replaylab.model.SourceContextResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class SourceCodeContextService {

    private static final int MAX_SCANNED_FILES = 5_000;
    private static final long MAX_FILE_SIZE = 1_000_000;
    private static final int MAX_EXCERPT_COUNT = 8;
    private static final int MAX_EXCERPT_CHARACTERS = 4_000;
    private static final int MAX_SEARCH_TERMS = 40;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java",
            ".kt",
            ".xml",
            ".yml",
            ".yaml",
            ".properties",
            ".json",
            ".js",
            ".ts",
            ".tsx"
    );

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git",
            ".idea",
            "target",
            "build",
            "dist",
            "node_modules",
            "work",
            "data",
            "logs",
            "log"
    );

    private static final Set<String> SENSITIVE_FILE_PARTS = Set.of(
            ".env",
            "secret",
            "credential",
            "password",
            "keystore",
            "truststore",
            "private-key",
            "private_key"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "this",
            "that",
            "with",
            "from",
            "when",
            "then",
            "should",
            "could",
            "would",
            "after",
            "before",
            "order",
            "issue",
            "error",
            "status",
            "current",
            "expected",
            "problem",
            "result",
            "step",
            "steps",
            "http",
            "https"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_-]{3,}"
    );

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9]*"
                    + "(?:Controller|Service|Client|Adapter|"
                    + "Mapper|Handler|Processor|Exception)\\b"
    );

    private final GitWorkspaceService gitWorkspaceService;
    private final EvidenceSanitizer evidenceSanitizer;

    public SourceCodeContextService(
            GitWorkspaceService gitWorkspaceService,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.gitWorkspaceService = gitWorkspaceService;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    public SourceContextResult collect(
            ReplayCaseEntity replayCase,
            Target target,
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals incidentSignals,
            IncidentTimeline timeline
    ) {
        ResolvedSourceRoot resolvedRoot = resolveSourceRoot(
                replayCase,
                target
        );

        List<String> searchTerms = buildSearchTerms(
                jiraIssue,
                plainDescription,
                incidentSignals,
                timeline
        );

        if (resolvedRoot.path() == null) {
            return new SourceContextResult(
                    resolvedRoot.mode(),
                    target.getRepository(),
                    0,
                    searchTerms,
                    List.of(),
                    resolvedRoot.warning()
            );
        }

        return scan(
                resolvedRoot,
                target.getRepository(),
                searchTerms
        );
    }

    public SourceContextResult collectFromRoot(
            Path sourceRoot,
            String repository,
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals incidentSignals,
            IncidentTimeline timeline
    ) {
        Path normalizedRoot = sourceRoot.toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(normalizedRoot)) {
            return new SourceContextResult(
                    "BITBUCKET_READ_ONLY",
                    repository,
                    0,
                    List.of(),
                    List.of(),
                    "Checkout directory does not exist: "
                            + normalizedRoot
            );
        }

        List<String> searchTerms = buildSearchTerms(
                jiraIssue,
                plainDescription,
                incidentSignals,
                timeline
        );

        return scan(
                new ResolvedSourceRoot(
                        normalizedRoot,
                        "BITBUCKET_READ_ONLY",
                        ""
                ),
                repository,
                searchTerms
        );
    }

    private ResolvedSourceRoot resolveSourceRoot(
            ReplayCaseEntity replayCase,
            Target target
    ) {
        String localSourcePath = target.getLocalSourcePath();

        if (localSourcePath != null && !localSourcePath.isBlank()) {
            Path localRoot = Path.of(localSourcePath)
                    .toAbsolutePath()
                    .normalize();

            if (!Files.isDirectory(localRoot)) {
                return new ResolvedSourceRoot(
                        null,
                        "LOCAL",
                        "Configured local source path does not exist: "
                                + localRoot
                );
            }

            return new ResolvedSourceRoot(
                    localRoot,
                    "LOCAL",
                    ""
            );
        }

        try {
            Path workspace = gitWorkspaceService.prepare(
                    replayCase,
                    target
            );

            return new ResolvedSourceRoot(
                    workspace
                            .toAbsolutePath()
                            .normalize(),
                    "GIT_WORKSPACE",
                    ""
            );
        } catch (Exception exception) {
            return new ResolvedSourceRoot(
                    null,
                    "UNAVAILABLE",
                    rootCauseMessage(exception)
            );
        }
    }

    private SourceContextResult scan(
            ResolvedSourceRoot resolvedRoot,
            String repository,
            List<String> searchTerms
    ) {
        AtomicInteger scannedFileCount = new AtomicInteger();
        List<ScoredFile> candidates = new ArrayList<>();

        try (Stream<Path> files = Files.walk(resolvedRoot.path())) {
            files.filter(Files::isRegularFile)
                    .filter(path ->
                            !isExcluded(
                                    resolvedRoot.path(),
                                    path
                            )
                    )
                    .filter(this::isAllowedFile)
                    .limit(MAX_SCANNED_FILES)
                    .forEach(path -> {
                        scannedFileCount.incrementAndGet();
                        ScoredFile candidate = scoreFile(
                                resolvedRoot.path(),
                                path,
                                searchTerms
                        );

                        if (candidate != null && candidate.score() > 0) {
                            candidates.add(candidate);
                        }
                    });
        } catch (Exception exception) {
            return new SourceContextResult(
                    resolvedRoot.mode(),
                    repository,
                    scannedFileCount.get(),
                    searchTerms,
                    List.of(),
                    rootCauseMessage(exception)
            );
        }

        List<SourceExcerpt> excerpts = candidates.stream()
                .sorted(
                        Comparator
                                .comparingInt(
                                        ScoredFile::score
                                )
                                .reversed()
                                .thenComparing(
                                        candidate -> candidate.path()
                                                .toString()
                                )
                )
                .limit(MAX_EXCERPT_COUNT)
                .map(candidate ->
                        createExcerpt(
                                resolvedRoot.path(),
                                candidate,
                                searchTerms
                        )
                )
                .filter(excerpt ->
                        excerpt != null
                                && !excerpt.content()
                                .isBlank()
                )
                .toList();

        String warning = excerpts.isEmpty()
                ? "No source file matched the extracted incident signals."
                : "";

        return new SourceContextResult(
                resolvedRoot.mode(),
                repository,
                scannedFileCount.get(),
                searchTerms,
                excerpts,
                warning
        );
    }

    private ScoredFile scoreFile(
            Path root,
            Path path,
            List<String> searchTerms
    ) {
        try {
            if (Files.size(path) > MAX_FILE_SIZE) {
                return null;
            }

            String content = Files.readString(
                    path,
                    StandardCharsets.UTF_8
            );

            String relativePath = root.relativize(path)
                    .toString()
                    .replace('\\', '/');

            String lowerPath = relativePath.toLowerCase(
                    Locale.ROOT
            );

            String lowerContent = content.toLowerCase(
                    Locale.ROOT
            );

            int score = 0;

            for (String term : searchTerms) {
                String lowerTerm = term.toLowerCase(
                        Locale.ROOT
                );

                if (lowerPath.contains(lowerTerm)) {
                    score += 20;
                }

                if (lowerContent.contains(lowerTerm)) {
                    score += 6;
                }
            }

            if (lowerPath.contains("controller")
                    || lowerPath.contains("service")
                    || lowerPath.contains("client")
                    || lowerPath.contains("adapter")
                    || lowerPath.contains("integration")
                    || lowerPath.contains("mapper")
                    || lowerPath.contains("handler")) {
                score += 5;
            }

            return score == 0
                    ? null
                    : new ScoredFile(
                    path,
                    score,
                    content
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private SourceExcerpt createExcerpt(
            Path root,
            ScoredFile candidate,
            List<String> searchTerms
    ) {
        String[] lines = candidate.content()
                .split("\\R", -1);

        int matchLine = findBestMatchLine(
                lines,
                searchTerms
        );

        int startLine = Math.max(
                0,
                matchLine - 20
        );

        int endLine = Math.min(
                lines.length,
                matchLine + 50
        );

        StringBuilder excerpt = new StringBuilder();

        for (int index = startLine; index < endLine; index++) {
            excerpt.append(index + 1)
                    .append(" | ")
                    .append(lines[index])
                    .append('\n');

            if (excerpt.length() >= MAX_EXCERPT_CHARACTERS) {
                break;
            }
        }

        String sanitized = evidenceSanitizer.sanitize(
                excerpt.toString()
        );

        if (sanitized.length() > MAX_EXCERPT_CHARACTERS) {
            sanitized = sanitized.substring(
                    0,
                    MAX_EXCERPT_CHARACTERS
            );
        }

        String relativePath = root.relativize(candidate.path())
                .toString()
                .replace('\\', '/');

        return new SourceExcerpt(
                relativePath,
                sanitized
        );
    }

    private int findBestMatchLine(
            String[] lines,
            List<String> searchTerms
    ) {
        for (int index = 0; index < lines.length; index++) {
            String lowerLine = lines[index].toLowerCase(
                    Locale.ROOT
            );

            for (String term : searchTerms) {
                if (lowerLine.contains(
                        term.toLowerCase(
                                Locale.ROOT
                        )
                )) {
                    return index;
                }
            }
        }

        return 0;
    }

    private List<String> buildSearchTerms(
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals signals,
            IncidentTimeline timeline
    ) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();

        if (signals != null) {
            addValues(
                    terms,
                    signals.endpoints()
            );

            addEndpointSegments(
                    terms,
                    signals.endpoints()
            );

            addValues(
                    terms,
                    signals.businessTerms()
            );
            
            // Expand business terms into code-like variants
            addExpandedCodeVariants(
                    terms,
                    signals.businessTerms()
            );

            addValues(
                    terms,
                    signals.errorCodes()
            );
        }

        addWords(
                terms,
                jiraIssue == null
                        ? ""
                        : jiraIssue.summary()
        );

        addClassNames(
                terms,
                plainDescription
        );

        if (timeline != null && timeline.events() != null) {
            timeline.events()
                    .stream()
                    .limit(50)
                    .forEach(event -> {
                        addValue(
                                terms,
                                event.endpoint()
                        );

                        addEndpointSegments(
                                terms,
                                List.of(
                                        safe(
                                                event.endpoint()
                                        )
                                )
                        );

                        addClassNames(
                                terms,
                                event.message()
                        );
                    });
        }

        return terms.stream()
                .limit(MAX_SEARCH_TERMS)
                .toList();
    }

    private void addEndpointSegments(
            Set<String> target,
            List<String> endpoints
    ) {
        if (endpoints == null) {
            return;
        }

        for (String endpoint : endpoints) {
            if (endpoint == null) {
                continue;
            }

            for (String segment : endpoint.split("[/_\\-.]+")) {
                addValue(
                        target,
                        segment
                );
            }
        }
    }

    private void addWords(
            Set<String> target,
            String text
    ) {
        if (text == null) {
            return;
        }

        Matcher matcher = WORD_PATTERN.matcher(text);

        while (matcher.find()) {
            addValue(
                    target,
                    matcher.group()
            );
        }
    }

    private void addClassNames(
            Set<String> target,
            String text
    ) {
        if (text == null) {
            return;
        }

        Matcher matcher = CLASS_PATTERN.matcher(text);

        while (matcher.find()) {
            addValue(
                    target,
                    matcher.group()
            );
        }
    }

    private void addValues(
            Set<String> target,
            List<String> values
    ) {
        if (values == null) {
            return;
        }

        values.forEach(value ->
                addValue(
                        target,
                        value
                )
        );
    }

    private void addValue(
            Set<String> target,
            String value
    ) {
        if (value == null) {
            return;
        }

        String normalized = value.trim()
                .toLowerCase(
                        Locale.ROOT
                );

        if (normalized.length() < 3
                || normalized.length() > 150
                || STOP_WORDS.contains(normalized)) {
            return;
        }

        target.add(normalized);
    }
    
    private void addExpandedCodeVariants(
            Set<String> target,
            List<String> businessTerms
    ) {
        if (businessTerms == null) {
            return;
        }
        
        for (String term : businessTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            
            String normalized = term.trim().toLowerCase(Locale.ROOT);
            
            // Generate variants for different naming conventions
            List<String> variants = expandTermVariants(normalized);
            
            for (String variant : variants) {
                addValue(target, variant);
            }
        }
    }
    
    private List<String> expandTermVariants(String term) {
        List<String> variants = new ArrayList<>();
        
        // Original term
        variants.add(term);
        
        // Handle snake_case -> camelCase
        if (term.contains("_")) {
            String[] parts = term.split("_");
            
            // camelCase: first lowercase, rest capitalized
            StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].length() > 0) {
                    camelCase.append(Character.toUpperCase(parts[i].charAt(0)))
                            .append(parts[i].substring(1).toLowerCase());
                }
            }
            variants.add(camelCase.toString());
            
            // PascalCase: all parts capitalized
            StringBuilder pascalCase = new StringBuilder();
            for (String part : parts) {
                if (part.length() > 0) {
                    pascalCase.append(Character.toUpperCase(part.charAt(0)))
                            .append(part.substring(1).toLowerCase());
                }
            }
            variants.add(pascalCase.toString());
            
            // kebab-case
            variants.add(String.join("-", parts));
        }
        
        // Handle kebab-case -> camelCase
        if (term.contains("-")) {
            String[] parts = term.split("-");
            
            // camelCase
            StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].length() > 0) {
                    camelCase.append(Character.toUpperCase(parts[i].charAt(0)))
                            .append(parts[i].substring(1).toLowerCase());
                }
            }
            variants.add(camelCase.toString());
            
            // PascalCase
            StringBuilder pascalCase = new StringBuilder();
            for (String part : parts) {
                if (part.length() > 0) {
                    pascalCase.append(Character.toUpperCase(part.charAt(0)))
                            .append(part.substring(1).toLowerCase());
                }
            }
            variants.add(pascalCase.toString());
            
            // snake_case
            variants.add(String.join("_", parts));
        }
        
        // Handle spaces -> multiple variants
        if (term.contains(" ")) {
            String[] parts = term.split("\\s+");
            
            // camelCase
            StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].length() > 0) {
                    camelCase.append(Character.toUpperCase(parts[i].charAt(0)))
                            .append(parts[i].substring(1).toLowerCase());
                }
            }
            variants.add(camelCase.toString());
            
            // PascalCase
            StringBuilder pascalCase = new StringBuilder();
            for (String part : parts) {
                if (part.length() > 0) {
                    pascalCase.append(Character.toUpperCase(part.charAt(0)))
                            .append(part.substring(1).toLowerCase());
                }
            }
            variants.add(pascalCase.toString());
            
            // snake_case
            variants.add(String.join("_", parts).toLowerCase());
            
            // kebab-case
            variants.add(String.join("-", parts).toLowerCase());
            
            // Each word individually
            for (String part : parts) {
                if (part.length() >= 3) {
                    variants.add(part.toLowerCase());
                }
            }
        }
        
        // Add common suffixes for Java classes
        if (!term.contains("service") && !term.contains("controller") && 
            !term.contains("repository") && !term.contains("entity")) {
            String base = term.replace("_", "").replace("-", "");
            variants.add(base + "service");
            variants.add(base + "controller");
            variants.add(base + "repository");
            variants.add(base + "entity");
            
            // PascalCase versions
            if (base.length() > 0) {
                String pascalBase = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                variants.add(pascalBase + "Service");
                variants.add(pascalBase + "Controller");
                variants.add(pascalBase + "Repository");
                variants.add(pascalBase + "Entity");
            }
        }
        
        return variants;
    }

    private boolean isExcluded(
            Path root,
            Path path
    ) {
        Path relative = root.relativize(path);

        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(
                    part.toString()
                            .toLowerCase(
                                    Locale.ROOT
                            )
            )) {
                return true;
            }
        }

        String fileName = path.getFileName()
                .toString()
                .toLowerCase(
                        Locale.ROOT
                );

        return SENSITIVE_FILE_PARTS.stream()
                .anyMatch(fileName::contains);
    }

    private boolean isAllowedFile(Path path) {
        String fileName = path.getFileName()
                .toString()
                .toLowerCase(
                        Locale.ROOT
                );

        if ("pom.xml".equals(fileName)
                || "build.gradle".equals(fileName)
                || "build.gradle.kts".equals(fileName)) {
            return true;
        }

        return ALLOWED_EXTENSIONS.stream()
                .anyMatch(fileName::endsWith);
    }

    private String rootCauseMessage(
            Throwable throwable
    ) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
                + ": "
                + root.getMessage();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ResolvedSourceRoot(
            Path path,
            String mode,
            String warning
    ) {
    }

    private record ScoredFile(
            Path path,
            int score,
            String content
    ) {
    }
}

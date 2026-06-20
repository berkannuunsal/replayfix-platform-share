package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.SourceCheckoutResult;
import com.etiya.replayfix.model.SourceSuspectCandidateFile;
import com.etiya.replayfix.model.SourceSuspectCandidateMethod;
import com.etiya.replayfix.model.SourceSuspectScanResponse;
import com.etiya.replayfix.model.SourceSuspectSnippet;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class SourceSuspectScanService {

    public static final String SOURCE_WORKSPACE_NOT_FOUND =
            "SOURCE_WORKSPACE_NOT_FOUND";

    private static final int MAX_SCANNED_FILES = 20_000;
    private static final long MAX_FILE_SIZE = 1_000_000;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java",
            ".xml",
            ".yml",
            ".yaml",
            ".properties",
            ".sql",
            ".ts",
            ".tsx",
            ".js",
            ".jsx"
    );

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target",
            "build",
            "node_modules",
            ".git",
            "dist",
            "out",
            "generated",
            "generated-sources",
            "generated-test-sources"
    );

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|return\\b|new\\b)"
                    + "(?:(?:public|protected|private|static|final|native|"
                    + "synchronized|abstract|default|strictfp)\\s+)*"
                    + "[A-Za-z_$][A-Za-z0-9_$<>\\[\\], ?]*\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;]*\\)"
                    + "\\s*(?:throws\\s+[^{]+)?\\{?.*$"
    );

    private final SuspectSignalExtractionService signalExtractionService;
    private final ReplayFixProperties properties;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public SourceSuspectScanService(
            SuspectSignalExtractionService signalExtractionService,
            ReplayFixProperties properties,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.signalExtractionService = signalExtractionService;
        this.properties = properties;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SourceSuspectScanResponse scan(
            UUID caseId,
            int maxFiles,
            int maxSnippetsPerFile,
            boolean includeWeak
    ) {
        SuspectSignalExtractionResponse signals =
                signalExtractionService.extract(caseId, includeWeak);

        List<String> warnings = new ArrayList<>(signals.warnings());
        List<String> searchSignals = signals.signals()
                .stream()
                .map(SuspectSourceSignal::value)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();

        Optional<Path> sourceRoot = locateWorkspace(caseId, signals.repository());
        if (sourceRoot.isEmpty()) {
            warnings.add(SOURCE_WORKSPACE_NOT_FOUND);
            return new SourceSuspectScanResponse(
                    caseId,
                    signals.jiraKey(),
                    signals.repository(),
                    signals.branch(),
                    searchSignals.size(),
                    0,
                    0,
                    List.of(),
                    warnings
            );
        }

        List<SourceSuspectCandidateFile> candidateFiles =
                scanWorkspace(
                        sourceRoot.get(),
                        searchSignals,
                        Math.max(1, maxFiles),
                        Math.max(1, maxSnippetsPerFile),
                        warnings
                );

        int methodCount = candidateFiles.stream()
                .mapToInt(file -> file.candidateMethods().size())
                .sum();

        return new SourceSuspectScanResponse(
                caseId,
                signals.jiraKey(),
                signals.repository(),
                signals.branch(),
                searchSignals.size(),
                candidateFiles.size(),
                methodCount,
                candidateFiles,
                warnings
        );
    }

    private List<SourceSuspectCandidateFile> scanWorkspace(
            Path root,
            List<String> searchSignals,
            int maxFiles,
            int maxSnippetsPerFile,
            List<String> warnings
    ) {
        if (searchSignals.isEmpty()) {
            return List.of();
        }

        List<SourceSuspectCandidateFile> candidates = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !isExcluded(root, path))
                    .filter(this::isAllowedFile)
                    .limit(MAX_SCANNED_FILES)
                    .forEach(path -> scanFile(
                            root,
                            path,
                            searchSignals,
                            maxSnippetsPerFile
                    ).ifPresent(candidates::add));
        } catch (Exception exception) {
            warnings.add("SOURCE_SCAN_FAILED: " + rootCauseMessage(exception));
        }

        return candidates.stream()
                .sorted(Comparator
                        .comparingInt(SourceSuspectCandidateFile::matchCount)
                        .reversed()
                        .thenComparing(SourceSuspectCandidateFile::relativePath))
                .limit(maxFiles)
                .toList();
    }

    private Optional<SourceSuspectCandidateFile> scanFile(
            Path root,
            Path path,
            List<String> searchSignals,
            int maxSnippetsPerFile
    ) {
        try {
            if (Files.size(path) > MAX_FILE_SIZE) {
                return Optional.empty();
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            MatchResult matches = matchSignals(content, searchSignals);
            if (matches.matchCount() == 0) {
                return Optional.empty();
            }

            String relativePath = root.relativize(path)
                    .toString()
                    .replace('\\', '/');
            String[] lines = content.split("\\R", -1);
            List<SourceSuspectSnippet> snippets =
                    snippets(lines, matches.lineSignals(), maxSnippetsPerFile);

            String className = null;
            List<SourceSuspectCandidateMethod> methods = List.of();
            if (relativePath.toLowerCase(Locale.ROOT).endsWith(".java")) {
                className = extractClassName(content);
                methods = extractCandidateMethods(lines, matches.lineSignals());
            }

            return Optional.of(new SourceSuspectCandidateFile(
                    relativePath,
                    fileType(path),
                    matches.matchedSignals(),
                    matches.matchCount(),
                    snippets,
                    className,
                    methods,
                    confidence(methods, snippets),
                    List.of()
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private MatchResult matchSignals(
            String content,
            List<String> searchSignals
    ) {
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String[] lines = content.split("\\R", -1);
        List<String> matchedSignals = new ArrayList<>();
        Map<Integer, LinkedHashSet<String>> lineSignals = new LinkedHashMap<>();
        int matchCount = 0;

        for (String signal : searchSignals) {
            String lowerSignal = signal.toLowerCase(Locale.ROOT);
            int count = countOccurrences(lowerContent, lowerSignal);
            if (count == 0) {
                continue;
            }

            matchedSignals.add(signal);
            matchCount += count;

            for (int index = 0; index < lines.length; index++) {
                if (lines[index].toLowerCase(Locale.ROOT)
                        .contains(lowerSignal)) {
                    lineSignals.computeIfAbsent(
                                    index + 1,
                                    ignored -> new LinkedHashSet<>()
                            )
                            .add(signal);
                }
            }
        }

        return new MatchResult(
                List.copyOf(matchedSignals),
                matchCount,
                lineSignals
        );
    }

    private int countOccurrences(String content, String signal) {
        if (signal.isBlank()) {
            return 0;
        }

        int count = 0;
        int fromIndex = 0;
        while (fromIndex < content.length()) {
            int index = content.indexOf(signal, fromIndex);
            if (index < 0) {
                break;
            }
            count++;
            fromIndex = index + signal.length();
        }
        return count;
    }

    private List<SourceSuspectSnippet> snippets(
            String[] lines,
            Map<Integer, LinkedHashSet<String>> lineSignals,
            int maxSnippetsPerFile
    ) {
        return lineSignals.entrySet()
                .stream()
                .limit(maxSnippetsPerFile)
                .map(entry -> new SourceSuspectSnippet(
                        entry.getKey(),
                        lines[entry.getKey() - 1].trim(),
                        List.copyOf(entry.getValue())
                ))
                .toList();
    }

    private String extractClassName(String content) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<SourceSuspectCandidateMethod> extractCandidateMethods(
            String[] lines,
            Map<Integer, LinkedHashSet<String>> lineSignals
    ) {
        List<JavaMethodContext> methods = extractMethods(lines);
        List<SourceSuspectCandidateMethod> candidates = new ArrayList<>();

        for (JavaMethodContext method : methods) {
            LinkedHashSet<String> matchedSignals = new LinkedHashSet<>();
            for (Map.Entry<Integer, LinkedHashSet<String>> entry
                    : lineSignals.entrySet()) {
                int lineNumber = entry.getKey();
                if (lineNumber >= method.startLine()
                        && lineNumber <= method.endLine()) {
                    matchedSignals.addAll(entry.getValue());
                }
            }

            if (!matchedSignals.isEmpty()) {
                candidates.add(new SourceSuspectCandidateMethod(
                        method.name(),
                        method.declarationLine(),
                        method.annotations(),
                        List.copyOf(matchedSignals)
                ));
            }
        }

        return candidates;
    }

    private List<JavaMethodContext> extractMethods(String[] lines) {
        List<JavaMethodContext> methods = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        int annotationStartLine = -1;

        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("@")) {
                if (pendingAnnotations.isEmpty()) {
                    annotationStartLine = index + 1;
                }
                pendingAnnotations.add(trimmed);
                continue;
            }

            Matcher matcher = METHOD_PATTERN.matcher(lines[index]);
            if (matcher.matches()) {
                String methodName = matcher.group(1);
                int declarationLine = index + 1;
                int startLine = annotationStartLine > 0
                        ? annotationStartLine
                        : declarationLine;
                int endLine = findMethodEndLine(lines, index);

                methods.add(new JavaMethodContext(
                        methodName,
                        startLine,
                        declarationLine,
                        endLine,
                        List.copyOf(mappingAnnotations(pendingAnnotations))
                ));
                pendingAnnotations.clear();
                annotationStartLine = -1;
                continue;
            }

            if (!trimmed.isBlank() && !trimmed.startsWith("//")) {
                pendingAnnotations.clear();
                annotationStartLine = -1;
            }
        }

        return methods;
    }

    private List<String> mappingAnnotations(List<String> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.contains("Mapping"))
                .toList();
    }

    private int findMethodEndLine(String[] lines, int methodIndex) {
        int depth = 0;
        boolean opened = false;

        for (int index = methodIndex; index < lines.length; index++) {
            String line = lines[index];
            for (int charIndex = 0; charIndex < line.length(); charIndex++) {
                char character = line.charAt(charIndex);
                if (character == '{') {
                    depth++;
                    opened = true;
                } else if (character == '}') {
                    depth--;
                    if (opened && depth <= 0) {
                        return index + 1;
                    }
                }
            }
        }

        return Math.min(lines.length, methodIndex + 80);
    }

    private String confidence(
            List<SourceSuspectCandidateMethod> methods,
            List<SourceSuspectSnippet> snippets
    ) {
        if (!methods.isEmpty()) {
            return "METHOD_CONTEXT_MATCH";
        }
        if (!snippets.isEmpty()) {
            return "FILE_CONTENT_MATCH";
        }
        return "UNSCORED";
    }

    private Optional<Path> locateWorkspace(UUID caseId, String repository) {
        List<Path> candidates = new ArrayList<>();
        latestSourceCheckoutWorkspace(caseId).ifPresent(candidates::add);

        String slug = repositorySlug(repository);
        Path caseWorkspace = Path.of(
                        properties.getWorkspaceDir(),
                        caseId.toString()
                )
                .toAbsolutePath()
                .normalize();

        if (!slug.isBlank()) {
            candidates.add(caseWorkspace.resolve("repositories")
                    .resolve(slug)
                    .normalize());
        }
        candidates.add(caseWorkspace.resolve("repository").normalize());

        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst();
    }

    private Optional<Path> latestSourceCheckoutWorkspace(UUID caseId) {
        return evidenceRepository
                .findByCaseIdAndEvidenceType(caseId, EvidenceType.SOURCE_CHECKOUT)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .flatMap(this::workspaceFromEvidence);
    }

    private Optional<Path> workspaceFromEvidence(EvidenceEntity evidence) {
        String content = firstNonBlank(evidence.getContentText(), evidence.getBody());
        if (content == null) {
            return Optional.empty();
        }

        try {
            SourceCheckoutResult result =
                    objectMapper.readValue(content, SourceCheckoutResult.class);
            if (result.workspace() != null && !result.workspace().isBlank()) {
                return Optional.of(Path.of(result.workspace())
                        .toAbsolutePath()
                        .normalize());
            }
        } catch (Exception ignored) {
            try {
                JsonNode node = objectMapper.readTree(content);
                JsonNode workspace = node.get("workspace");
                if (workspace != null && workspace.isTextual()) {
                    return Optional.of(Path.of(workspace.asText())
                            .toAbsolutePath()
                            .normalize());
                }
            } catch (Exception ignoredAgain) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String value = part.toString().toLowerCase(Locale.ROOT);
            if (EXCLUDED_DIRECTORIES.contains(value)
                    || value.equals("generated")
                    || value.startsWith("generated-")
                    || value.endsWith("-generated")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedFile(Path path) {
        String fileName = path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private String fileType(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String repositorySlug(String repository) {
        if (repository == null || repository.isBlank()) {
            return "";
        }
        int slash = repository.lastIndexOf('/');
        return slash >= 0 ? repository.substring(slash + 1) : repository;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private record MatchResult(
            List<String> matchedSignals,
            int matchCount,
            Map<Integer, LinkedHashSet<String>> lineSignals
    ) {
    }

    private record JavaMethodContext(
            String name,
            int startLine,
            int declarationLine,
            int endLine,
            List<String> annotations
    ) {
    }
}

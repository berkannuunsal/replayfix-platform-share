package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.SourceSuspectCandidateFile;
import com.etiya.replayfix.model.SourceSuspectCandidateMethod;
import com.etiya.replayfix.model.SourceSuspectScanResponse;
import com.etiya.replayfix.model.SourceSuspectSnippet;
import com.etiya.replayfix.model.SuspectSignalCategory;
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
    public static final String SOURCE_WORKSPACE_EMPTY_OR_NO_SUPPORTED_FILES =
            "SOURCE_WORKSPACE_EMPTY_OR_NO_SUPPORTED_FILES";
    public static final String NO_SOURCE_SIGNAL_MATCHES_FOUND =
            "NO_SOURCE_SIGNAL_MATCHES_FOUND";
    public static final String SOURCE_BRANCH_NOT_RESOLVED =
            "SOURCE_BRANCH_NOT_RESOLVED";

    private static final int MAX_SCANNED_FILES = 20_000;
    private static final long MAX_FILE_SIZE = 1_000_000;
    private static final int USED_SIGNAL_PREVIEW_LIMIT = 50;

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

    private static final Set<String> SECRET_PATH_MARKERS = Set.of(
            "token",
            "secret",
            "password",
            "credential",
            "apikey",
            "api-key"
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
        List<SearchSignal> searchSignals = searchSignals(signals.signals());
        ResolutionContext resolutionContext =
                resolutionContext(caseId, signals.repository(), signals.branch());

        String branch = firstNonBlank(signals.branch(), resolutionContext.branch());
        if (branch == null) {
            warnings.add(SOURCE_BRANCH_NOT_RESOLVED);
        }

        WorkspaceResolution workspaceResolution =
                locateWorkspace(caseId, resolutionContext);
        if (workspaceResolution.path().isEmpty()) {
            warnings.add(SOURCE_WORKSPACE_NOT_FOUND);
            return response(
                    caseId,
                    signals,
                    branch,
                    searchSignals,
                    resolutionContext,
                    null,
                    false,
                    ScanResult.empty(),
                    List.of(),
                    warnings
            );
        }

        Path root = workspaceResolution.path().get();
        ScanResult scanResult =
                scanWorkspace(
                        root,
                        searchSignals,
                        Math.max(1, maxFiles),
                        Math.max(1, maxSnippetsPerFile),
                        warnings
                );

        if (scanResult.scannedFileCount() == 0) {
            warnings.add(SOURCE_WORKSPACE_EMPTY_OR_NO_SUPPORTED_FILES);
        } else if (scanResult.candidateFiles().isEmpty()) {
            warnings.add(NO_SOURCE_SIGNAL_MATCHES_FOUND);
        }

        return response(
                caseId,
                signals,
                branch,
                searchSignals,
                resolutionContext,
                root,
                true,
                scanResult,
                scanResult.candidateFiles(),
                warnings
        );
    }

    private SourceSuspectScanResponse response(
            UUID caseId,
            SuspectSignalExtractionResponse signals,
            String branch,
            List<SearchSignal> searchSignals,
            ResolutionContext resolutionContext,
            Path scannedRoot,
            boolean workspaceResolved,
            ScanResult scanResult,
            List<SourceSuspectCandidateFile> candidateFiles,
            List<String> warnings
    ) {
        int methodCount = candidateFiles.stream()
                .mapToInt(file -> file.candidateMethods().size())
                .sum();

        return new SourceSuspectScanResponse(
                caseId,
                signals.jiraKey(),
                signals.repository(),
                blankToEmpty(branch),
                searchSignals.size(),
                candidateFiles.size(),
                methodCount,
                candidateFiles,
                List.copyOf(new LinkedHashSet<>(warnings)),
                scannedRoot == null ? "" : sanitizePath(scannedRoot),
                workspaceResolved,
                scanResult.scannedFileCount(),
                scanResult.scannedDirectoryCount(),
                scanResult.skippedDirectoryCount(),
                scanResult.fileExtensionCounts(),
                searchSignals.size(),
                searchSignals.stream()
                        .map(SearchSignal::value)
                        .limit(USED_SIGNAL_PREVIEW_LIMIT)
                        .toList(),
                blankToEmpty(resolutionContext.repositorySlug()),
                blankToEmpty(resolutionContext.repositoryName())
        );
    }

    private List<SearchSignal> searchSignals(
            List<SuspectSourceSignal> signals
    ) {
        Map<String, SearchSignal> values = new LinkedHashMap<>();

        for (SuspectSourceSignal signal : signals) {
            if (signal.value() == null || signal.value().isBlank()) {
                continue;
            }
            String value = signal.value().trim();
            values.putIfAbsent(
                    value,
                    new SearchSignal(
                            value,
                            isCaseSensitiveSignal(signal)
                    )
            );
        }

        return values.values().stream().toList();
    }

    private boolean isCaseSensitiveSignal(SuspectSourceSignal signal) {
        return signal.category() == SuspectSignalCategory.CONSTANT
                || signal.value().matches("[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+");
    }

    private ScanResult scanWorkspace(
            Path root,
            List<SearchSignal> searchSignals,
            int maxFiles,
            int maxSnippetsPerFile,
            List<String> warnings
    ) {
        if (searchSignals.isEmpty()) {
            return ScanResult.empty();
        }

        MutableScanResult result = new MutableScanResult();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.forEach(path -> inspectPath(
                    root,
                    path,
                    searchSignals,
                    maxSnippetsPerFile,
                    result
            ));
        } catch (Exception exception) {
            warnings.add("SOURCE_SCAN_FAILED: " + rootCauseMessage(exception));
        }

        List<SourceSuspectCandidateFile> limitedCandidates =
                result.candidateFiles()
                        .stream()
                        .sorted(Comparator
                                .comparingInt(SourceSuspectCandidateFile::matchCount)
                                .reversed()
                                .thenComparing(SourceSuspectCandidateFile::relativePath))
                        .limit(maxFiles)
                        .toList();

        return new ScanResult(
                result.scannedFileCount(),
                result.scannedDirectoryCount(),
                result.skippedDirectoryCount(),
                Map.copyOf(result.fileExtensionCounts()),
                limitedCandidates
        );
    }

    private void inspectPath(
            Path root,
            Path path,
            List<SearchSignal> searchSignals,
            int maxSnippetsPerFile,
            MutableScanResult result
    ) {
        if (path.equals(root)) {
            return;
        }

        if (Files.isDirectory(path)) {
            result.incrementScannedDirectoryCount();
            if (isExcluded(root, path)) {
                result.incrementSkippedDirectoryCount();
            }
            return;
        }

        if (!Files.isRegularFile(path) || isExcluded(root, path)) {
            return;
        }

        String extension = fileType(path);
        if (!isAllowedFile(path)) {
            return;
        }

        if (result.scannedFileCount() >= MAX_SCANNED_FILES) {
            return;
        }

        result.incrementScannedFileCount();
        result.incrementExtension(extension);

        scanFile(
                root,
                path,
                searchSignals,
                maxSnippetsPerFile
        ).ifPresent(result::addCandidateFile);
    }

    private Optional<SourceSuspectCandidateFile> scanFile(
            Path root,
            Path path,
            List<SearchSignal> searchSignals,
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
            List<SearchSignal> searchSignals
    ) {
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String[] lines = content.split("\\R", -1);
        List<String> matchedSignals = new ArrayList<>();
        Map<Integer, LinkedHashSet<String>> lineSignals = new LinkedHashMap<>();
        int matchCount = 0;

        for (SearchSignal signal : searchSignals) {
            String haystack = signal.caseSensitive()
                    ? content
                    : lowerContent;
            String needle = signal.caseSensitive()
                    ? signal.value()
                    : signal.value().toLowerCase(Locale.ROOT);
            int count = countOccurrences(haystack, needle);
            if (count == 0) {
                continue;
            }

            matchedSignals.add(signal.value());
            matchCount += count;

            for (int index = 0; index < lines.length; index++) {
                String line = signal.caseSensitive()
                        ? lines[index]
                        : lines[index].toLowerCase(Locale.ROOT);
                if (line.contains(needle)) {
                    lineSignals.computeIfAbsent(
                                    index + 1,
                                    ignored -> new LinkedHashSet<>()
                            )
                            .add(signal.value());
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

    private ResolutionContext resolutionContext(
            UUID caseId,
            String repository,
            String branch
    ) {
        ResolutionContext sourceContext =
                latestEvidence(caseId, EvidenceType.SOURCE_CONTEXT)
                        .flatMap(this::resolutionFromSourceContext)
                        .orElse(ResolutionContext.empty());

        ResolutionContext repositoryContext =
                latestEvidence(caseId, EvidenceType.REPOSITORY_RESOLUTION)
                        .flatMap(this::resolutionFromRepositoryResolution)
                        .orElse(ResolutionContext.empty());

        String repositorySlug = firstNonBlank(
                repositoryContext.repositorySlug(),
                sourceContext.repositorySlug(),
                repositorySlug(repository)
        );
        String repositoryName = firstNonBlank(
                repositoryContext.repositoryName(),
                sourceContext.repositoryName()
        );

        List<Path> sourceWorkspacePaths = new ArrayList<>();
        sourceWorkspacePaths.addAll(sourceContext.workspaceCandidates());
        sourceWorkspacePaths.addAll(repositoryContext.workspaceCandidates());

        return new ResolutionContext(
                firstNonBlank(
                        repositoryContext.projectKey(),
                        sourceContext.projectKey()
                ),
                repositorySlug,
                repositoryName,
                firstNonBlank(
                        branch,
                        repositoryContext.branch(),
                        sourceContext.branch(),
                        "backend".equalsIgnoreCase(repositorySlug)
                                ? "test2"
                                : null
                ),
                sourceWorkspacePaths
        );
    }

    private Optional<ResolutionContext> resolutionFromSourceContext(
            EvidenceEntity evidence
    ) {
        JsonNode node = readJson(evidence).orElse(null);
        if (node == null) {
            return Optional.empty();
        }

        List<Path> workspaceCandidates = new ArrayList<>();
        findWorkspaceTexts(node).forEach(value ->
                workspaceCandidates.add(Path.of(value)
                        .toAbsolutePath()
                        .normalize())
        );

        return Optional.of(new ResolutionContext(
                findText(node, "projectKey").orElse(null),
                findText(node, "repositorySlug", "repositoryName")
                        .orElse(null),
                findText(node, "repositoryName", "repository")
                        .orElse(null),
                findText(node, "sourceBranch", "branch", "targetBranch")
                        .orElse(null),
                workspaceCandidates
        ));
    }

    private Optional<ResolutionContext> resolutionFromRepositoryResolution(
            EvidenceEntity evidence
    ) {
        JsonNode node = readJson(evidence).orElse(null);
        if (node == null) {
            return Optional.empty();
        }

        String primarySlug = findText(
                node,
                "repositorySlug",
                "primaryRepositorySlug",
                "slug"
        ).orElse(null);

        JsonNode candidate = primarySlug == null
                ? null
                : matchingCandidate(node, primarySlug).orElse(null);

        String repositoryName = firstNonBlank(
                findText(node, "repositoryName", "name").orElse(null),
                candidate == null
                        ? null
                        : findText(candidate, "repositoryName", "name")
                        .orElse(null)
        );

        String branch = firstNonBlank(
                findText(
                        node,
                        "sourceBranch",
                        "branch",
                        "targetBranch",
                        "defaultBranch"
                ).orElse(null),
                candidate == null
                        ? null
                        : findText(candidate, "sourceBranch", "branch",
                        "defaultBranch").orElse(null)
        );

        return Optional.of(new ResolutionContext(
                findText(node, "projectKey").orElse(null),
                primarySlug,
                repositoryName,
                branch,
                List.of()
        ));
    }

    private Optional<JsonNode> matchingCandidate(JsonNode node, String slug) {
        JsonNode candidates = node.get("candidates");
        if (candidates == null || !candidates.isArray()) {
            return Optional.empty();
        }

        for (JsonNode candidate : candidates) {
            String candidateSlug = findText(
                    candidate,
                    "repositorySlug",
                    "primaryRepositorySlug",
                    "slug"
            ).orElse(null);
            if (slug.equals(candidateSlug)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    private WorkspaceResolution locateWorkspace(
            UUID caseId,
            ResolutionContext context
    ) {
        List<Path> candidates = new ArrayList<>();

        Path caseWorkspace = Path.of(
                        properties.getWorkspaceDir(),
                        caseId.toString()
                )
                .toAbsolutePath()
                .normalize();

        if (context.repositorySlug() != null
                && !context.repositorySlug().isBlank()) {
            candidates.add(caseWorkspace.resolve("repositories")
                    .resolve(context.repositorySlug())
                    .normalize());
        }

        if (context.repositoryName() != null
                && !context.repositoryName().isBlank()) {
            candidates.add(caseWorkspace.resolve("repositories")
                    .resolve(context.repositoryName())
                    .normalize());
        }

        candidates.addAll(context.workspaceCandidates());
        candidates.add(caseWorkspace.resolve("repository").normalize());

        properties.getTargets()
                .values()
                .stream()
                .map(ReplayFixProperties.Target::getLocalSourcePath)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .forEach(candidates::add);

        Path replayPackages = Path.of("replay-packages")
                .toAbsolutePath()
                .normalize();

        if (context.repositorySlug() != null
                && !context.repositorySlug().isBlank()) {
            candidates.add(replayPackages
                    .resolve(context.repositorySlug())
                    .normalize());
        }

        if (context.repositoryName() != null
                && !context.repositoryName().isBlank()) {
            candidates.add(replayPackages
                    .resolve(context.repositoryName())
                    .normalize());
        }

        return new WorkspaceResolution(
                candidates.stream()
                        .distinct()
                        .filter(Files::isDirectory)
                        .findFirst(),
                candidates
        );
    }

    private Optional<EvidenceEntity> latestEvidence(
            UUID caseId,
            EvidenceType evidenceType
    ) {
        return evidenceRepository
                .findByCaseIdAndEvidenceType(caseId, evidenceType)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private Optional<JsonNode> readJson(EvidenceEntity evidence) {
        String content = firstNonBlank(evidence.getContentText(), evidence.getBody());
        if (content == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Optional<String> value = findTextField(node, fieldName);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findTextField(JsonNode node, String fieldName) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null && (value.isTextual()
                    || value.isNumber()
                    || value.isBoolean())) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return Optional.of(text);
                }
            }

            var fields = node.fields();
            while (fields.hasNext()) {
                Optional<String> childValue =
                        findTextField(fields.next().getValue(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> childValue =
                        findTextField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private List<String> findWorkspaceTexts(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectWorkspaceTexts(node, values);
        return values;
    }

    private void collectWorkspaceTexts(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode value = entry.getValue();
                if (isWorkspaceField(fieldName)
                        && value.isTextual()
                        && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
                collectWorkspaceTexts(value, values);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectWorkspaceTexts(child, values);
            }
        }
    }

    private boolean isWorkspaceField(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return lower.equals("workspace")
                || lower.equals("sourceroot")
                || lower.equals("scannedroot")
                || lower.equals("repositorypath")
                || lower.equals("localsourcepath");
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

    private String sanitizePath(Path path) {
        String value = path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
        String[] parts = value.split("/");
        for (int index = 0; index < parts.length; index++) {
            String lower = parts[index].toLowerCase(Locale.ROOT);
            for (String marker : SECRET_PATH_MARKERS) {
                if (lower.contains(marker)) {
                    parts[index] = "***";
                    break;
                }
            }
        }
        return String.join("/", parts)
                .replaceAll("(?i)(token|secret|password|credential)=([^/&\\s]+)",
                        "$1=***")
                .replaceAll("(?i)(https?://)[^/@\\s]+@",
                        "$1***@");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private record SearchSignal(
            String value,
            boolean caseSensitive
    ) {
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

    private record ResolutionContext(
            String projectKey,
            String repositorySlug,
            String repositoryName,
            String branch,
            List<Path> workspaceCandidates
    ) {
        private static ResolutionContext empty() {
            return new ResolutionContext(
                    null,
                    null,
                    null,
                    null,
                    List.of()
            );
        }
    }

    private record WorkspaceResolution(
            Optional<Path> path,
            List<Path> candidates
    ) {
    }

    private record ScanResult(
            int scannedFileCount,
            int scannedDirectoryCount,
            int skippedDirectoryCount,
            Map<String, Integer> fileExtensionCounts,
            List<SourceSuspectCandidateFile> candidateFiles
    ) {
        private static ScanResult empty() {
            return new ScanResult(
                    0,
                    0,
                    0,
                    Map.of(),
                    List.of()
            );
        }
    }

    private static final class MutableScanResult {
        private int scannedFileCount;
        private int scannedDirectoryCount;
        private int skippedDirectoryCount;
        private final Map<String, Integer> fileExtensionCounts =
                new LinkedHashMap<>();
        private final List<SourceSuspectCandidateFile> candidateFiles =
                new ArrayList<>();

        private int scannedFileCount() {
            return scannedFileCount;
        }

        private void incrementScannedFileCount() {
            scannedFileCount++;
        }

        private int scannedDirectoryCount() {
            return scannedDirectoryCount;
        }

        private void incrementScannedDirectoryCount() {
            scannedDirectoryCount++;
        }

        private int skippedDirectoryCount() {
            return skippedDirectoryCount;
        }

        private void incrementSkippedDirectoryCount() {
            skippedDirectoryCount++;
        }

        private Map<String, Integer> fileExtensionCounts() {
            return fileExtensionCounts;
        }

        private void incrementExtension(String extension) {
            fileExtensionCounts.merge(extension, 1, Integer::sum);
        }

        private List<SourceSuspectCandidateFile> candidateFiles() {
            return candidateFiles;
        }

        private void addCandidateFile(SourceSuspectCandidateFile file) {
            candidateFiles.add(file);
        }
    }
}

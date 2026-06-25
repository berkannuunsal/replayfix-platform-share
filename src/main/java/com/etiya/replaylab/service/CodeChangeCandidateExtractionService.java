package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryCandidateHint;
import com.etiya.replaylab.api.dto.CodeChangeCandidateExtractionResponse;
import com.etiya.replaylab.api.dto.CodeChangeCandidateSummary;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.BitbucketSourceReadClient;
import com.etiya.replaylab.integration.BitbucketSourceReadClient.SourceFileFetchResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeChangeCandidateExtractionService {

    private static final Set<String> DEFAULT_EXTENSIONS =
            Set.of(".java", ".ts", ".tsx");
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "access_token",
            "refresh_token",
            "id_token",
            "password",
            "privateKey",
            "apiKey",
            "secret"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayLabProperties properties;
    private final ObjectMapper objectMapper;
    private final BitbucketSourceReadClient bitbucketSourceReadClient;

    public CodeChangeCandidateExtractionService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayLabProperties properties,
            ObjectMapper objectMapper,
            BitbucketSourceReadClient bitbucketSourceReadClient
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.bitbucketSourceReadClient = bitbucketSourceReadClient;
    }

    @Transactional(readOnly = true)
    public CodeChangeCandidateExtractionResponse extract(
            UUID caseId,
            int maxCandidates,
            int maxSnippetChars,
            boolean includeSnippetPreview
    ) {
        ReplayCaseEntity replayCase = loadCase(caseId);
        Extraction extraction = extractInternal(
                replayCase,
                Math.max(1, maxCandidates),
                Math.max(1, maxSnippetChars)
        );
        List<CodeChangeCandidateSummary> summaries = extraction.candidates()
                .stream()
                .map(candidate -> summary(candidate, includeSnippetPreview))
                .toList();
        return new CodeChangeCandidateExtractionResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                extraction.sourceCandidateSource(),
                summaries.size(),
                summaries,
                extraction.blockers(),
                extraction.warnings(),
                extraction.missingEvidence()
        );
    }

    @Transactional(readOnly = true)
    public List<CodeChangeAdvisoryCandidateHint> extractCandidateHints(
            UUID caseId,
            int maxCandidates,
            int maxSnippetChars
    ) {
        ReplayCaseEntity replayCase = loadCase(caseId);
        return extractInternal(
                replayCase,
                Math.max(1, maxCandidates),
                Math.max(1, maxSnippetChars)
        ).candidates()
                .stream()
                .map(candidate -> new CodeChangeAdvisoryCandidateHint(
                        candidate.repositoryLogicalName(),
                        candidate.filePath(),
                        candidate.classOrComponentName(),
                        candidate.methodName(),
                        candidate.language(),
                        candidate.snippet(),
                        "",
                        "",
                        candidateConstraints(candidate)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public HydratedCandidateHints hydrateCandidateHints(
            UUID caseId,
            List<CodeChangeAdvisoryCandidateHint> candidateHints,
            int maxCandidates,
            int maxSnippetChars
    ) {
        ReplayCaseEntity replayCase = loadCase(caseId);
        Hydration hydration = hydrateInternal(
                replayCase,
                candidateHints,
                Math.max(1, maxCandidates),
                Math.max(1, maxSnippetChars)
        );
        return new HydratedCandidateHints(
                hydration.candidates()
                        .stream()
                        .filter(ExtractedCandidate::usable)
                        .map(this::hint)
                        .toList(),
                hydration.candidates()
                        .stream()
                        .map(candidate -> summary(candidate, false))
                        .toList(),
                hydration.sourceCandidateSource(),
                hydration.blockers(),
                hydration.warnings(),
                hydration.missingEvidence()
        );
    }

    @Transactional(readOnly = true)
    public CodeChangeCandidateExtractionResponse hydrate(
            UUID caseId,
            List<CodeChangeAdvisoryCandidateHint> candidateHints,
            int maxCandidates,
            int maxSnippetChars,
            boolean includeSnippetPreview
    ) {
        ReplayCaseEntity replayCase = loadCase(caseId);
        Hydration hydration = hydrateInternal(
                replayCase,
                candidateHints,
                Math.max(1, maxCandidates),
                Math.max(1, maxSnippetChars)
        );
        List<CodeChangeCandidateSummary> summaries = hydration.candidates()
                .stream()
                .map(candidate -> summary(candidate, includeSnippetPreview))
                .toList();
        return new CodeChangeCandidateExtractionResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                hydration.sourceCandidateSource(),
                (int) hydration.candidates().stream()
                        .filter(ExtractedCandidate::usable)
                        .count(),
                summaries,
                hydration.blockers(),
                hydration.warnings(),
                hydration.missingEvidence()
        );
    }

    private Extraction extractInternal(
            ReplayCaseEntity replayCase,
            int maxCandidates,
            int maxSnippetChars
    ) {
        ReplayLabProperties.Target target = properties.getTargets()
                .getOrDefault(
                        replayCase.getTargetKey(),
                        new ReplayLabProperties.Target()
                );
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        if (!target.isSourceCandidateExtractionEnabled()) {
            blockers.add("SOURCE_CANDIDATE_EXTRACTION_DISABLED");
            return new Extraction(
                    List.of(),
                    sourceCandidateSource(target),
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        String source = sourceCandidateSource(target);
        if ("BITBUCKET".equalsIgnoreCase(source)) {
            return extractFromBitbucket(
                    replayCase,
                    target,
                    maxCandidates,
                    maxSnippetChars
            );
        }
        return extractFromWorkspace(
                replayCase,
                target,
                maxCandidates,
                maxSnippetChars,
                source
        );
    }

    private Extraction extractFromWorkspace(
            ReplayCaseEntity replayCase,
            ReplayLabProperties.Target target,
            int maxCandidates,
            int maxSnippetChars,
            String source
    ) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        Path root = workspaceRoot(target);
        if (root == null) {
            blockers.add("SOURCE_WORKSPACE_ROOT_NOT_CONFIGURED");
            return new Extraction(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        if (!Files.isDirectory(root)) {
            blockers.add("SOURCE_WORKSPACE_ROOT_NOT_FOUND");
            return new Extraction(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        List<CandidateSpec> specs = sourceAnalysisSpecs(
                replayCase.getId(),
                Math.min(maxCandidates, Math.max(1,
                        target.getMaxSourceCandidateFiles()))
        );
        if (specs.isEmpty()) {
            missingEvidence.add("SOURCE_ANALYSIS_CANDIDATES_NOT_FOUND");
            return new Extraction(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }

        List<ExtractedCandidate> candidates = new ArrayList<>();
        Set<String> extensions = allowedExtensions(target);
        for (CandidateSpec spec : specs) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            ExtractedCandidate candidate = extractCandidate(
                    root,
                    extensions,
                    spec,
                    maxSnippetChars
            );
            warnings.addAll(candidate.warnings());
            missingEvidence.addAll(candidate.missingEvidence());
            if (candidate.usable()) {
                candidates.add(candidate);
            }
        }
        return new Extraction(
                candidates,
                source,
                distinct(blockers),
                distinct(warnings),
                distinct(missingEvidence)
        );
    }

    private Extraction extractFromBitbucket(
            ReplayCaseEntity replayCase,
            ReplayLabProperties.Target target,
            int maxCandidates,
            int maxSnippetChars
    ) {
        String source = "BITBUCKET";
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        ReplayLabProperties.SourceCandidateBitbucket bitbucket =
                target.getBitbucket();
        if (bitbucket == null || bitbucket.getBaseUrl().isBlank()) {
            blockers.add("BITBUCKET_SOURCE_CONFIG_NOT_CONFIGURED");
            return new Extraction(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        List<CandidateSpec> specs = sourceAnalysisSpecs(
                replayCase.getId(),
                Math.min(maxCandidates, Math.max(1,
                        target.getMaxSourceCandidateFiles()))
        );
        if (specs.isEmpty()) {
            missingEvidence.add("SOURCE_ANALYSIS_CANDIDATES_NOT_FOUND");
            return new Extraction(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }

        List<ExtractedCandidate> candidates = new ArrayList<>();
        Set<String> targetExtensions = allowedExtensions(target);
        for (CandidateSpec spec : specs) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            ExtractedCandidate candidate = extractBitbucketCandidate(
                    target,
                    bitbucket,
                    targetExtensions,
                    spec,
                    maxSnippetChars
            );
            blockers.addAll(candidate.blockers());
            warnings.addAll(candidate.warnings());
            missingEvidence.addAll(candidate.missingEvidence());
            if (candidate.usable()) {
                candidates.add(candidate);
            }
        }
        return new Extraction(
                candidates,
                source,
                distinct(blockers),
                distinct(warnings),
                distinct(missingEvidence)
        );
    }

    private Hydration hydrateInternal(
            ReplayCaseEntity replayCase,
            List<CodeChangeAdvisoryCandidateHint> candidateHints,
            int maxCandidates,
            int maxSnippetChars
    ) {
        ReplayLabProperties.Target target = properties.getTargets()
                .getOrDefault(
                        replayCase.getTargetKey(),
                        new ReplayLabProperties.Target()
                );
        String source = sourceCandidateSource(target);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        if (!target.isSourceCandidateExtractionEnabled()) {
            blockers.add("SOURCE_CANDIDATE_EXTRACTION_DISABLED");
            return new Hydration(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        if (!"BITBUCKET".equalsIgnoreCase(source)) {
            blockers.add("BITBUCKET_SOURCE_PROVIDER_NOT_CONFIGURED");
            return new Hydration(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        ReplayLabProperties.SourceCandidateBitbucket bitbucket =
                target.getBitbucket();
        if (bitbucket == null || bitbucket.getBaseUrl().isBlank()) {
            blockers.add("BITBUCKET_SOURCE_CONFIG_NOT_CONFIGURED");
            return new Hydration(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        int candidateLimit = Math.min(
                maxCandidates,
                Math.max(1, target.getMaxSourceCandidateFiles())
        );
        List<CodeChangeAdvisoryCandidateHint> hints = candidateHints == null
                ? List.of()
                : candidateHints.stream().limit(candidateLimit).toList();
        if (hints.isEmpty()) {
            missingEvidence.add("CANDIDATE_HINTS_REQUIRED");
            return new Hydration(
                    List.of(),
                    source,
                    blockers,
                    warnings,
                    missingEvidence
            );
        }
        List<ExtractedCandidate> candidates = new ArrayList<>();
        Set<String> targetExtensions = allowedExtensions(target);
        for (CodeChangeAdvisoryCandidateHint hint : hints) {
            CandidateSpec spec = new CandidateSpec(
                    safe(hint.repositoryLogicalName()),
                    safe(hint.filePath()),
                    safe(hint.classOrComponentName()),
                    safe(hint.methodName()),
                    safe(hint.language())
            );
            ExtractedCandidate candidate = extractBitbucketCandidate(
                    target,
                    bitbucket,
                    targetExtensions,
                    spec,
                    maxSnippetChars
            );
            candidates.add(candidate);
            blockers.addAll(candidate.blockers());
            warnings.addAll(candidate.warnings());
            missingEvidence.addAll(candidate.missingEvidence());
        }
        return new Hydration(
                candidates,
                source,
                distinct(blockers),
                distinct(warnings),
                distinct(missingEvidence)
        );
    }

    private ExtractedCandidate extractCandidate(
            Path root,
            Set<String> extensions,
            CandidateSpec spec,
            int maxSnippetChars
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        Path resolved;
        try {
            resolved = resolvePath(root, spec.filePath());
        } catch (IllegalArgumentException exception) {
            warnings.add(exception.getMessage());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        String extension = extension(resolved.getFileName().toString());
        if (!extensions.contains(extension)) {
            warnings.add("SOURCE_FILE_EXTENSION_UNSUPPORTED:" + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        if (!Files.isRegularFile(resolved)) {
            missingEvidence.add("SOURCE_FILE_NOT_FOUND:" + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        String content;
        try {
            content = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            warnings.add("SOURCE_FILE_READ_FAILED:" + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        String snippet = extractSnippet(content, spec, extension, maxSnippetChars);
        if (snippet.isBlank()) {
            missingEvidence.add("METHOD_SNIPPET_MISSING:" + spec.filePath());
        return new ExtractedCandidate(
                spec.filePath(),
                spec.classOrComponentName(),
                spec.methodName(),
                language(extension, spec.language()),
                "",
                List.of("source workspace extraction attempted"),
                List.of(),
                missingEvidence,
                warnings,
                "LOCAL_WORKSPACE",
                "",
                "",
                "",
                "",
                ""
        );
        }
        if (snippet.length() > maxSnippetChars) {
            warnings.add("SOURCE_SNIPPET_TOO_LARGE:" + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        Optional<String> marker = sensitiveMarker(snippet);
        if (marker.isPresent()) {
            warnings.add("SOURCE_SNIPPET_SENSITIVE_MARKER_REJECTED:"
                    + marker.get());
            return ExtractedCandidate.unusable(
                    spec,
                    List.of(),
                    warnings,
                    missingEvidence,
                    "LOCAL_WORKSPACE",
                    null,
                    ""
            );
        }
        return new ExtractedCandidate(
                spec.filePath(),
                spec.classOrComponentName(),
                spec.methodName(),
                language(extension, spec.language()),
                snippet,
                List.of("source workspace bounded snippet"),
                List.of(),
                missingEvidence,
                warnings,
                "LOCAL_WORKSPACE",
                "",
                "",
                "",
                "",
                ""
        );
    }

    private ExtractedCandidate extractBitbucketCandidate(
            ReplayLabProperties.Target target,
            ReplayLabProperties.SourceCandidateBitbucket bitbucket,
            Set<String> targetExtensions,
            CandidateSpec spec,
            int maxSnippetChars
    ) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        Optional<String> pathProblem = remotePathProblem(spec.filePath());
        if (pathProblem.isPresent()) {
            warnings.add(pathProblem.get());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    null,
                    ""
            );
        }
        String extension = extension(spec.filePath());
        if (!targetExtensions.contains(extension)) {
            warnings.add("SOURCE_FILE_EXTENSION_UNSUPPORTED:"
                    + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    null,
                    ""
            );
        }
        ReplayLabProperties.SourceCandidateRepository repository =
                selectRepository(bitbucket, spec, extension).orElse(null);
        if (repository == null) {
            missingEvidence.add("REPOSITORY_MAPPING_UNKNOWN:"
                    + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    null,
                    ""
            );
        }
        Set<String> repositoryExtensions = repositoryExtensions(
                repository,
                targetExtensions
        );
        if (!repositoryExtensions.contains(extension)) {
            warnings.add("SOURCE_FILE_EXTENSION_UNSUPPORTED:"
                    + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    ""
            );
        }
        String branch = firstNonBlank(
                target.getSourceCandidateExtractionBranch(),
                repository.getBranch(),
                "test2"
        );
        String normalizedPath = normalizeRemotePath(repository, spec.filePath());
        Optional<String> normalizedPathProblem = remotePathProblem(normalizedPath);
        if (normalizedPathProblem.isPresent()) {
            warnings.add(normalizedPathProblem.get());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    branch,
                    normalizedPath
            );
        }
        SourceFileFetchResult fetched = bitbucketSourceReadClient.fetchRawFile(
                bitbucket,
                repository,
                normalizedPath,
                branch
        );
        warnings.addAll(fetched.warnings());
        if (!fetched.success()) {
            if (BitbucketSourceReadClient.CREDENTIALS_NOT_CONFIGURED
                    .equals(fetched.status())
                    || BitbucketSourceReadClient.READ_NOT_AUTHORIZED
                    .equals(fetched.status())) {
                blockers.add(fetched.status());
            } else if (BitbucketSourceReadClient.FILE_NOT_FOUND
                    .equals(fetched.status())) {
                missingEvidence.add("BITBUCKET_FILE_NOT_FOUND:"
                        + normalizedPath);
            } else {
                warnings.add(fetched.status());
            }
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    branch,
                    normalizedPath
            );
        }
        Optional<String> contentMarker = sensitiveMarker(fetched.content());
        if (contentMarker.isPresent()) {
            warnings.add("BITBUCKET_SOURCE_SENSITIVE_MARKER_REJECTED:"
                    + contentMarker.get());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    branch,
                    normalizedPath
            );
        }
        String snippet = extractSnippet(
                fetched.content(),
                spec,
                extension,
                maxSnippetChars
        );
        if (snippet.isBlank()) {
            missingEvidence.add("METHOD_SNIPPET_MISSING:" + spec.filePath());
            return new ExtractedCandidate(
                    spec.filePath(),
                    spec.classOrComponentName(),
                    spec.methodName(),
                    language(extension, spec.language()),
                    "",
                    List.of("bitbucket raw file extraction attempted"),
                    blockers,
                    missingEvidence,
                    warnings,
                    "BITBUCKET",
                    repository.getLogicalName(),
                    repository.getProjectKey(),
                    repository.getRepositorySlug(),
                    branch,
                    normalizedPath
            );
        }
        if (snippet.length() > maxSnippetChars) {
            warnings.add("SOURCE_SNIPPET_TOO_LARGE:" + spec.filePath());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    branch,
                    normalizedPath
            );
        }
        Optional<String> snippetMarker = sensitiveMarker(snippet);
        if (snippetMarker.isPresent()) {
            warnings.add("SOURCE_SNIPPET_SENSITIVE_MARKER_REJECTED:"
                    + snippetMarker.get());
            return ExtractedCandidate.unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    "BITBUCKET",
                    repository,
                    branch,
                    normalizedPath
            );
        }
        return new ExtractedCandidate(
                spec.filePath(),
                spec.classOrComponentName(),
                spec.methodName(),
                language(extension, spec.language()),
                snippet,
                List.of("bitbucket bounded raw-file snippet"),
                blockers,
                missingEvidence,
                warnings,
                "BITBUCKET",
                repository.getLogicalName(),
                repository.getProjectKey(),
                repository.getRepositorySlug(),
                branch,
                normalizedPath
        );
    }

    private String extractSnippet(
            String content,
            CandidateSpec spec,
            String extension,
            int maxSnippetChars
    ) {
        if (!spec.methodName().isBlank()) {
            return methodSnippet(content, spec.methodName(), extension);
        }
        if (!spec.classOrComponentName().isBlank()) {
            return boundedContext(
                    content,
                    spec.classOrComponentName(),
                    maxSnippetChars
            );
        }
        return "";
    }

    private String methodSnippet(
            String content,
            String methodName,
            String extension
    ) {
        Pattern pattern = ".java".equals(extension)
                ? Pattern.compile(
                "(?m)^\\s*(?:public|protected|private|static|final|synchronized|\\s)+[^\\n;=]*\\b"
                        + Pattern.quote(methodName)
                        + "\\s*\\([^;]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{"
        )
                : Pattern.compile(
                "(?m)^\\s*(?:export\\s+)?(?:async\\s+)?(?:function\\s+"
                        + Pattern.quote(methodName)
                        + "\\s*\\([^)]*\\)|"
                        + Pattern.quote(methodName)
                        + "\\s*\\([^)]*\\)\\s*(?::\\s*[^=\\{]+)?\\{)"
        );
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            int index = content.indexOf(methodName + "(");
            if (index < 0) {
                return "";
            }
            int brace = content.indexOf('{', index);
            if (brace < 0) {
                return "";
            }
            int start = lineStart(content, index);
            int end = balancedEnd(content, brace);
            return end <= start ? "" : content.substring(start, end).trim();
        }
        int brace = content.indexOf('{', matcher.start());
        int end = balancedEnd(content, brace);
        return end <= matcher.start()
                ? ""
                : content.substring(matcher.start(), end).trim();
    }

    private String boundedContext(
            String content,
            String anchor,
            int maxSnippetChars
    ) {
        int index = content.indexOf(anchor);
        if (index < 0) {
            return "";
        }
        int start = Math.max(0, lineStart(content, index));
        int end = Math.min(content.length(), start + maxSnippetChars);
        return content.substring(start, end).trim();
    }

    private int balancedEnd(String content, int braceIndex) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = braceIndex; index < content.length(); index++) {
            char current = content.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (inString) {
                if (current == quote) {
                    inString = false;
                }
                continue;
            }
            if (current == '"' || current == '\'' || current == '`') {
                inString = true;
                quote = current;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        return -1;
    }

    private List<CandidateSpec> sourceAnalysisSpecs(
            UUID caseId,
            int maxCandidates
    ) {
        List<CandidateSpec> specs = new ArrayList<>();
        for (EvidenceType type : List.of(
                EvidenceType.SOURCE_CONTEXT,
                EvidenceType.AI_ROOT_CAUSE,
                EvidenceType.DETERMINISTIC_ROOT_CAUSE
        )) {
            latestEvidence(caseId, type)
                    .flatMap(this::readJson)
                    .ifPresent(json -> collectSpecs(json, specs, maxCandidates));
            if (specs.size() >= maxCandidates) {
                break;
            }
        }
        return specs.stream().limit(maxCandidates).toList();
    }

    private void collectSpecs(
            JsonNode node,
            List<CandidateSpec> specs,
            int maxCandidates
    ) {
        if (node == null || specs.size() >= maxCandidates) {
            return;
        }
        if (node.isObject()) {
            String filePath = firstText(node, "filePath", "file", "path");
            if (!filePath.isBlank()) {
                specs.add(new CandidateSpec(
                        "",
                        filePath,
                        firstText(
                                node,
                                "classOrComponentName",
                                "className",
                                "componentName"
                        ),
                        firstText(node, "methodName", "method"),
                        firstText(node, "language")
                ));
            }
            node.fields().forEachRemaining(entry -> collectSpecs(
                    entry.getValue(),
                    specs,
                    maxCandidates
            ));
        } else if (node.isArray()) {
            node.forEach(child -> collectSpecs(child, specs, maxCandidates));
        }
    }

    private Optional<EvidenceEntity> latestEvidence(
            UUID caseId,
            EvidenceType type
    ) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private Optional<JsonNode> readJson(EvidenceEntity evidence) {
        String content = evidence.getContentText() == null
                ? evidence.getBody()
                : evidence.getContentText();
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Path workspaceRoot(ReplayLabProperties.Target target) {
        String configured = firstNonBlank(
                target.getSourceWorkspaceRoot(),
                target.getLocalSourcePath()
        );
        if (configured.isBlank()) {
            return null;
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private Path resolvePath(Path root, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("SOURCE_FILE_PATH_MISSING");
        }
        if (filePath.contains("..")) {
            throw new IllegalArgumentException("SOURCE_PATH_TRAVERSAL_REJECTED");
        }
        Path raw = Path.of(filePath);
        Path resolved = raw.isAbsolute()
                ? raw.toAbsolutePath().normalize()
                : root.resolve(raw).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("SOURCE_FILE_OUTSIDE_WORKSPACE");
        }
        return resolved;
    }

    private Set<String> allowedExtensions(ReplayLabProperties.Target target) {
        if (target.getAllowedSourceExtensions() == null
                || target.getAllowedSourceExtensions().isEmpty()) {
            return DEFAULT_EXTENSIONS;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String extension : target.getAllowedSourceExtensions()) {
            if (extension != null && !extension.isBlank()) {
                values.add(extension.startsWith(".")
                        ? extension.toLowerCase(Locale.ROOT)
                        : "." + extension.toLowerCase(Locale.ROOT));
            }
        }
        return values.isEmpty() ? DEFAULT_EXTENSIONS : values;
    }

    private CodeChangeCandidateSummary summary(
            ExtractedCandidate candidate,
            boolean includeSnippetPreview
    ) {
        return new CodeChangeCandidateSummary(
                candidate.sourceCandidateSource(),
                candidate.repositoryLogicalName(),
                candidate.projectKey(),
                candidate.repositorySlug(),
                candidate.branch(),
                candidate.filePath(),
                candidate.normalizedFilePath(),
                candidate.classOrComponentName(),
                candidate.methodName(),
                candidate.language(),
                !candidate.snippet().isBlank(),
                candidate.snippet().length(),
                includeSnippetPreview
                        ? preview(candidate.snippet())
                        : "",
                candidate.missingEvidence(),
                candidate.warnings()
        );
    }

    private List<String> candidateConstraints(ExtractedCandidate candidate) {
        List<String> constraints = new ArrayList<>(candidate.constraints());
        if (!candidate.sourceCandidateSource().isBlank()) {
            constraints.add("sourceCandidateSource="
                    + candidate.sourceCandidateSource());
        }
        if (!candidate.snippet().isBlank()
                && "BITBUCKET".equals(candidate.sourceCandidateSource())) {
            constraints.add("hydratedFromSource=true");
        }
        if (!candidate.repositoryLogicalName().isBlank()) {
            constraints.add("repositoryLogicalName="
                    + candidate.repositoryLogicalName());
        }
        if (!candidate.branch().isBlank()) {
            constraints.add("branch=" + candidate.branch());
        }
        if (!candidate.normalizedFilePath().isBlank()) {
            constraints.add("normalizedFilePath="
                    + candidate.normalizedFilePath());
        }
        constraints.add("snippetChars=" + candidate.snippet().length());
        candidate.warnings().forEach(warning -> constraints.add(
                "hydrationWarning=" + warning));
        candidate.missingEvidence().forEach(missing -> constraints.add(
                "hydrationMissingEvidence=" + missing));
        return List.copyOf(constraints);
    }

    private String preview(String snippet) {
        String sanitized = snippet == null ? "" : snippet;
        for (String marker : SENSITIVE_MARKERS) {
            sanitized = sanitized.replaceAll(
                    "(?i)" + Pattern.quote(marker),
                    "[REDACTED]"
            );
        }
        return sanitized.length() <= 500
                ? sanitized
                : sanitized.substring(0, 500);
    }

    private Optional<String> sensitiveMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream()
                .filter(marker -> lower.contains(marker.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    private String language(String extension, String hint) {
        String normalized = hint == null
                ? ""
                : hint.toUpperCase(Locale.ROOT);
        if ("JAVA".equals(normalized) || "TYPESCRIPT".equals(normalized)) {
            return normalized;
        }
        return ".java".equals(extension) ? "JAVA" : "TYPESCRIPT";
    }

    private String sourceCandidateSource(ReplayLabProperties.Target target) {
        String source = target.getSourceCandidateSource();
        return source == null || source.isBlank()
                ? "LOCAL_WORKSPACE"
                : source.toUpperCase(Locale.ROOT);
    }

    private Optional<String> remotePathProblem(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.of("SOURCE_FILE_PATH_MISSING");
        }
        String normalized = filePath.replace("\\", "/");
        if (normalized.contains("..")) {
            return Optional.of("SOURCE_PATH_TRAVERSAL_REJECTED");
        }
        if (normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            return Optional.of("SOURCE_ABSOLUTE_PATH_REJECTED");
        }
        if (normalized.contains("://")) {
            return Optional.of("SOURCE_URL_PATH_REJECTED");
        }
        return Optional.empty();
    }

    private Optional<ReplayLabProperties.SourceCandidateRepository>
    selectRepository(
            ReplayLabProperties.SourceCandidateBitbucket bitbucket,
            CandidateSpec spec,
            String extension
    ) {
        Map<String, ReplayLabProperties.SourceCandidateRepository>
                repositories = bitbucket.getRepositories();
        if (repositories == null || repositories.isEmpty()) {
            return Optional.empty();
        }
        String requestedRepository = spec.repositoryLogicalName() == null
                ? ""
                : spec.repositoryLogicalName().trim();
        if (!requestedRepository.isBlank()) {
            Optional<ReplayLabProperties.SourceCandidateRepository> exact =
                    repositories.entrySet()
                            .stream()
                            .filter(entry -> requestedRepository
                                    .equalsIgnoreCase(entry.getKey())
                                    || requestedRepository.equalsIgnoreCase(
                                    entry.getValue().getLogicalName()))
                            .map(Map.Entry::getValue)
                            .findFirst();
            if (exact.isPresent()) {
                return exact;
            }
            return Optional.empty();
        }
        String language = spec.language() == null
                ? ""
                : spec.language().toUpperCase(Locale.ROOT);
        String normalizedPath = spec.filePath() == null
                ? ""
                : spec.filePath().replace("\\", "/")
                .toLowerCase(Locale.ROOT);
        if ("TYPESCRIPT".equals(language)
                || ".ts".equals(extension)
                || ".tsx".equals(extension)
                || normalizedPath.contains("frontend/customer-ui")) {
            return Optional.ofNullable(repositories.get("customerUi"))
                    .or(() -> Optional.ofNullable(
                            repositories.get("customer-ui")));
        }
        if ("JAVA".equals(language) || ".java".equals(extension)) {
            return Optional.ofNullable(repositories.get("backend"));
        }
        return Optional.empty();
    }

    private Set<String> repositoryExtensions(
            ReplayLabProperties.SourceCandidateRepository repository,
            Set<String> fallback
    ) {
        if (repository.getAllowedExtensions() == null
                || repository.getAllowedExtensions().isEmpty()) {
            return fallback;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String extension : repository.getAllowedExtensions()) {
            if (extension != null && !extension.isBlank()) {
                values.add(extension.startsWith(".")
                        ? extension.toLowerCase(Locale.ROOT)
                        : "." + extension.toLowerCase(Locale.ROOT));
            }
        }
        return values.isEmpty() ? fallback : values;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String normalizeRemotePath(
            ReplayLabProperties.SourceCandidateRepository repository,
            String filePath
    ) {
        String normalized = filePath == null
                ? ""
                : filePath.replace("\\", "/");
        String logicalName = repository == null
                ? ""
                : repository.getLogicalName().toLowerCase(Locale.ROOT);
        if ("backend".equals(logicalName)) {
            normalized = stripPrefix(normalized, "bss-backend/");
            normalized = stripPrefix(normalized, "backend/");
        } else if ("customer-ui".equals(logicalName)
                || "customerUi".equals(logicalName)) {
            normalized = stripPrefix(normalized, "frontend/customer-ui/");
            normalized = stripPrefix(normalized, "customer-ui/");
        }
        return normalized;
    }

    private String stripPrefix(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT)
                .startsWith(prefix.toLowerCase(Locale.ROOT))
                ? value.substring(prefix.length())
                : value;
    }

    private CodeChangeAdvisoryCandidateHint hint(
            ExtractedCandidate candidate
    ) {
        return new CodeChangeAdvisoryCandidateHint(
                candidate.repositoryLogicalName(),
                candidate.filePath(),
                candidate.classOrComponentName(),
                candidate.methodName(),
                candidate.language(),
                candidate.snippet(),
                "",
                "",
                candidateConstraints(candidate)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private int lineStart(String content, int index) {
        int newline = content.lastIndexOf('\n', Math.max(0, index));
        return newline < 0 ? 0 : newline + 1;
    }

    private ReplayCaseEntity loadCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private List<String> distinct(List<String> values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(distinct::add);
        }
        return List.copyOf(distinct);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record CandidateSpec(
            String repositoryLogicalName,
            String filePath,
            String classOrComponentName,
            String methodName,
            String language
    ) {
    }

    private record ExtractedCandidate(
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            String snippet,
            List<String> constraints,
            List<String> blockers,
            List<String> missingEvidence,
            List<String> warnings,
            String sourceCandidateSource,
            String repositoryLogicalName,
            String projectKey,
            String repositorySlug,
            String branch,
            String normalizedFilePath
    ) {
        private boolean usable() {
            return !filePath.isBlank()
                    && !snippet.isBlank()
                    && blockers.isEmpty()
                    && warnings.stream().noneMatch(value ->
                    value.contains("REJECTED")
                            || value.contains("OUTSIDE")
                            || value.contains("TRAVERSAL")
                            || value.contains("UNSUPPORTED")
                            || value.contains("TOO_LARGE"));
        }

        private static ExtractedCandidate unusable(
                CandidateSpec spec,
                List<String> blockers,
                List<String> warnings,
                List<String> missingEvidence,
                String sourceCandidateSource,
                ReplayLabProperties.SourceCandidateRepository repository,
                String branch
        ) {
            return unusable(
                    spec,
                    blockers,
                    warnings,
                    missingEvidence,
                    sourceCandidateSource,
                    repository,
                    branch,
                    ""
            );
        }

        private static ExtractedCandidate unusable(
                CandidateSpec spec,
                List<String> blockers,
                List<String> warnings,
                List<String> missingEvidence,
                String sourceCandidateSource,
                ReplayLabProperties.SourceCandidateRepository repository,
                String branch,
                String normalizedFilePath
        ) {
            return new ExtractedCandidate(
                    spec.filePath(),
                    spec.classOrComponentName(),
                    spec.methodName(),
                    spec.language(),
                    "",
                    List.of(),
                    blockers,
                    missingEvidence,
                    warnings,
                    sourceCandidateSource,
                    repository == null ? "" : repository.getLogicalName(),
                    repository == null ? "" : repository.getProjectKey(),
                    repository == null ? "" : repository.getRepositorySlug(),
                    branch,
                    normalizedFilePath
            );
        }
    }

    private record Extraction(
            List<ExtractedCandidate> candidates,
            String sourceCandidateSource,
            List<String> blockers,
            List<String> warnings,
            List<String> missingEvidence
    ) {
    }

    private record Hydration(
            List<ExtractedCandidate> candidates,
            String sourceCandidateSource,
            List<String> blockers,
            List<String> warnings,
            List<String> missingEvidence
    ) {
    }

    public record HydratedCandidateHints(
            List<CodeChangeAdvisoryCandidateHint> hints,
            List<CodeChangeCandidateSummary> summaries,
            String sourceCandidateSource,
            List<String> blockers,
            List<String> warnings,
            List<String> missingEvidence
    ) {
        public HydratedCandidateHints {
            hints = hints == null ? List.of() : List.copyOf(hints);
            summaries = summaries == null ? List.of() : List.copyOf(summaries);
            sourceCandidateSource = sourceCandidateSource == null
                    ? ""
                    : sourceCandidateSource;
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            missingEvidence = missingEvidence == null
                    ? List.of()
                    : List.copyOf(missingEvidence);
        }
    }
}

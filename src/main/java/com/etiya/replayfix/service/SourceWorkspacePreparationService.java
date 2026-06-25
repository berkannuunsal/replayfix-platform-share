package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.SourceWorkspacePreparationResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class SourceWorkspacePreparationService {

    public static final String WORKSPACE_EXISTS_BUT_EMPTY =
            "WORKSPACE_EXISTS_BUT_EMPTY";
    public static final String SOURCE_REPOSITORY_CREDENTIALS_NOT_CONFIGURED =
            "SOURCE_REPOSITORY_CREDENTIALS_NOT_CONFIGURED";
    public static final String REPOSITORY_RESOLUTION_NOT_FOUND =
            "REPOSITORY_RESOLUTION_NOT_FOUND";

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
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

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public SourceWorkspacePreparationService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SourceWorkspacePreparationResponse prepare(
            UUID caseId,
            boolean force
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Case not found: " + caseId
                ));
        List<String> warnings = new ArrayList<>();
        RepositoryResolution resolution =
                repositoryResolution(caseId).orElseGet(() -> {
                    warnings.add(REPOSITORY_RESOLUTION_NOT_FOUND);
                    return RepositoryResolution.empty();
                });

        String repositorySlug = firstNonBlank(
                resolution.repositorySlug(),
                replayCase.getTargetKey()
        );
        String projectKey = firstNonBlank(
                resolution.projectKey(),
                properties.getIntegrations().getBitbucket().getProjectKey()
        );
        String branch = firstNonBlank(
                resolution.sourceBranch(),
                replayCase.getSourceBranch(),
                "backend".equalsIgnoreCase(replayCase.getTargetKey())
                        ? "test2"
                        : null
        );
        String repository = projectKey == null || repositorySlug == null
                ? repositorySlug
                : projectKey + "/" + repositorySlug;

        Path workspace = Path.of(
                        properties.getWorkspaceDir(),
                        caseId.toString(),
                        "repositories",
                        blankToDefault(repositorySlug, "repository")
                )
                .toAbsolutePath()
                .normalize();

        int supportedFileCount = supportedFileCount(workspace);
        if (supportedFileCount > 0) {
            return response(
                    replayCase,
                    repository,
                    repositorySlug,
                    branch,
                    workspace,
                    true,
                    false,
                    supportedFileCount,
                    warnings
            );
        }

        if (Files.isDirectory(workspace) && !force) {
            warnings.add(WORKSPACE_EXISTS_BUT_EMPTY);
            return response(
                    replayCase,
                    repository,
                    repositorySlug,
                    branch,
                    workspace,
                    false,
                    false,
                    0,
                    warnings
            );
        }

        String cloneUrl = firstNonBlank(
                resolution.cloneUrl(),
                resolution.sanitizedCloneUrl(),
                buildCloneUrl(projectKey, repositorySlug)
        );
        if (!canClone(cloneUrl)) {
            warnings.add(SOURCE_REPOSITORY_CREDENTIALS_NOT_CONFIGURED);
            return response(
                    replayCase,
                    repository,
                    repositorySlug,
                    branch,
                    workspace,
                    false,
                    false,
                    0,
                    warnings
            );
        }

        try {
            if (Files.isDirectory(workspace) && force) {
                deleteWorkspace(workspace);
            }

            Files.createDirectories(workspace.getParent());
            cloneRepository(cloneUrl, branch, workspace);
            supportedFileCount = supportedFileCount(workspace);
            return response(
                    replayCase,
                    repository,
                    repositorySlug,
                    branch,
                    workspace,
                    supportedFileCount > 0,
                    true,
                    supportedFileCount,
                    warnings
            );
        } catch (Exception exception) {
            warnings.add("SOURCE_WORKSPACE_PREPARATION_FAILED");
            return response(
                    replayCase,
                    repository,
                    repositorySlug,
                    branch,
                    workspace,
                    false,
                    false,
                    supportedFileCount(workspace),
                    warnings
            );
        }
    }

    private SourceWorkspacePreparationResponse response(
            ReplayCaseEntity replayCase,
            String repository,
            String repositorySlug,
            String branch,
            Path workspace,
            boolean workspaceReady,
            boolean cloned,
            int supportedFileCount,
            List<String> warnings
    ) {
        return new SourceWorkspacePreparationResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                blankToEmpty(repository),
                blankToEmpty(repositorySlug),
                blankToEmpty(branch),
                relativeWorkspacePath(workspace),
                workspaceReady,
                cloned,
                supportedFileCount,
                List.copyOf(warnings)
        );
    }

    private Optional<RepositoryResolution> repositoryResolution(UUID caseId) {
        return evidenceRepository
                .findByCaseIdAndEvidenceType(
                        caseId,
                        EvidenceType.REPOSITORY_RESOLUTION
                )
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .flatMap(this::parseRepositoryResolution);
    }

    private Optional<RepositoryResolution> parseRepositoryResolution(
            EvidenceEntity evidence
    ) {
        String content = firstNonBlank(evidence.getContentText(), evidence.getBody());
        if (content == null) {
            return Optional.empty();
        }

        try {
            JsonNode node = objectMapper.readTree(content);
            String repositorySlug = findText(
                    node,
                    "repositorySlug",
                    "primaryRepositorySlug",
                    "slug"
            ).orElse(null);
            JsonNode candidate = repositorySlug == null
                    ? null
                    : matchingCandidate(node, repositorySlug).orElse(null);

            return Optional.of(new RepositoryResolution(
                    findText(node, "projectKey").orElse(null),
                    repositorySlug,
                    firstNonBlank(
                            findText(node, "repositoryName", "name")
                                    .orElse(null),
                            candidate == null
                                    ? null
                                    : findText(candidate, "repositoryName", "name")
                                    .orElse(null)
                    ),
                    firstNonBlank(
                            findText(node, "cloneUrl").orElse(null),
                            candidate == null
                                    ? null
                                    : findText(candidate, "cloneUrl")
                                    .orElse(null)
                    ),
                    findText(node, "sanitizedCloneUrl").orElse(null),
                    firstNonBlank(
                            findText(
                                    node,
                                    "sourceBranch",
                                    "branch",
                                    "targetBranch",
                                    "defaultBranch"
                            ).orElse(null),
                            candidate == null
                                    ? null
                                    : findText(candidate, "sourceBranch",
                                    "branch", "defaultBranch").orElse(null)
                    )
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
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

    private boolean canClone(String cloneUrl) {
        var bitbucket = properties.getIntegrations().getBitbucket();
        return cloneUrl != null
                && !cloneUrl.isBlank()
                && bitbucket.isEnabled()
                && bitbucket.getUsername() != null
                && !bitbucket.getUsername().isBlank()
                && bitbucket.getToken() != null
                && !bitbucket.getToken().isBlank();
    }

    private String buildCloneUrl(String projectKey, String repositorySlug) {
        String baseUrl = properties.getIntegrations().getBitbucket().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()
                || projectKey == null || projectKey.isBlank()
                || repositorySlug == null || repositorySlug.isBlank()) {
            return null;
        }
        return baseUrl.replaceAll("/+$", "")
                + "/scm/"
                + projectKey
                + "/"
                + repositorySlug
                + ".git";
    }

    private void cloneRepository(
            String cloneUrl,
            String branch,
            Path workspace
    ) throws Exception {
        var bitbucket = properties.getIntegrations().getBitbucket();
        Path askPassScript = createAskPassScript(workspace.getParent());
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_ASKPASS", askPassScript.toString());
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("REPLAYFIX_GIT_USERNAME", bitbucket.getUsername());
        environment.put("REPLAYFIX_GIT_TOKEN", bitbucket.getToken());

        try {
            List<String> command = new ArrayList<>();
            command.add(blankToDefault(bitbucket.getGitExecutable(), "git"));
            command.add("clone");
            command.add("--no-tags");
            command.add("--single-branch");
            if (branch != null && !branch.isBlank()) {
                command.add("--branch");
                command.add(branch);
            }
            command.add(cloneUrl);
            command.add(workspace.toString());
            run(null, command, environment);
        } finally {
            Files.deleteIfExists(askPassScript);
        }
    }

    private Path createAskPassScript(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path script = Files.createTempFile(
                directory,
                "replayfix-git-askpass-",
                ".sh"
        );
        Files.writeString(
                script,
                """
                        #!/bin/sh
                        case "$1" in
                        *Username*|*username*)
                        printf '%s\\n' "$REPLAYFIX_GIT_USERNAME"
                        ;;
                        *)
                        printf '%s\\n' "$REPLAYFIX_GIT_TOKEN"
                        ;;
                        esac
                        """,
                StandardCharsets.UTF_8
        );
        script.toFile().setExecutable(true);
        return script;
    }

    private String run(
            Path directory,
            List<String> command,
            Map<String, String> environment
    ) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            builder.environment().putAll(environment);
            Process process = builder.start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Command failed with exit code " + exitCode
                                + ": " + sanitizeCommand(command)
                                + "\n" + sanitizeOutput(output)
                );
            }
            return output;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Command execution failed: " + sanitizeCommand(command),
                    exception
            );
        }
    }

    private int supportedFileCount(Path workspace) {
        if (!Files.isDirectory(workspace)) {
            return 0;
        }

        try (Stream<Path> files = Files.walk(workspace)) {
            return (int) files
                    .filter(Files::isRegularFile)
                    .filter(path -> !isExcluded(workspace, path))
                    .filter(this::isSupportedFile)
                    .count();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean isSupportedFile(Path path) {
        String fileName = path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
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

    private void deleteWorkspace(Path workspace) throws Exception {
        Path expectedParent = Path.of(
                        properties.getWorkspaceDir()
                )
                .toAbsolutePath()
                .normalize();
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!normalizedWorkspace.startsWith(expectedParent)) {
            throw new IllegalArgumentException(
                    "Workspace path is outside ReplayLab workspace."
            );
        }
        try (Stream<Path> paths = Files.walk(normalizedWorkspace)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException(
                                    "Cannot delete workspace path.",
                                    exception
                            );
                        }
                    });
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

    private String relativeWorkspacePath(Path workspace) {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path normalized = workspace.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized)
                    .toString()
                    .replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private String sanitizeCommand(List<String> command) {
        List<String> sanitized = new ArrayList<>();
        for (String part : command) {
            sanitized.add(sanitizeOutput(part));
        }
        return String.join(" ", sanitized);
    }

    private String sanitizeOutput(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1***@")
                .replaceAll("(?i)(token|password|secret|credential)=([^\\s&]+)",
                        "$1=***");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RepositoryResolution(
            String projectKey,
            String repositorySlug,
            String repositoryName,
            String cloneUrl,
            String sanitizedCloneUrl,
            String sourceBranch
    ) {
        private static RepositoryResolution empty() {
            return new RepositoryResolution(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}

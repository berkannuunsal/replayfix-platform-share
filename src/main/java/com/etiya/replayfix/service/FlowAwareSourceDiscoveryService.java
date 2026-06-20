package com.etiya.replayfix.service;

import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceDiscoveredControllerEndpoint;
import com.etiya.replayfix.model.SourceFlowAnchor;
import org.springframework.stereotype.Service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class FlowAwareSourceDiscoveryService {

    private static final int DEFAULT_MAX_SCANNED_FILES = 2_000;
    private static final int DEFAULT_MAX_FILE_SIZE_KB = 256;
    private static final List<String> PREFERRED_SOURCE_DIRECTORIES = List.of(
            "CrmBackend",
            "BaseBackend",
            "ProdBackend",
            "OrderBackend",
            "BpmnBackend"
    );

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
            "generated-test-sources",
            "test-output"
    );

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+([A-Za-z0-9_.$*]+);",
            Pattern.MULTILINE
    );
    private static final Pattern MAPPING_PATTERN = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)"
                    + "\\s*(?:\\(([^)]*)\\))?"
    );
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:@[A-Za-z0-9_$.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|protected|private|static|final|native|"
                    + "synchronized|abstract|default|strictfp)\\s+)*"
                    + "([A-Za-z_$][A-Za-z0-9_$<>\\[\\], ?]*)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)"
                    + "\\s*(?:throws\\s+[^{]+)?\\{?.*$",
            Pattern.MULTILINE
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?:@Autowired\\s+)?(?:private|protected|public)?\\s*"
                    + "(?:final\\s+)?([A-Z][A-Za-z0-9_$]*)\\s+"
                    + "([a-zA-Z_$][A-Za-z0-9_$]*)\\s*(?:=|;)"
    );
    private static final Pattern CALL_PATTERN = Pattern.compile(
            "\\b([a-zA-Z_$][A-Za-z0-9_$]*)\\.([a-zA-Z_$][A-Za-z0-9_$]*)\\s*\\("
    );
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_$]*(?:Request|Response|Dto|DTO|Entity|Mapper|Validator|Repository)?)\\b"
    );
    private static final Pattern STRING_CONSTANT_PATTERN = Pattern.compile(
            "\\b(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?String\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*\"([^\"]*)\"\\s*;"
    );
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile(
            "\"([^\"]*)\""
    );

    public DiscoveryResult discover(
            Path root,
            List<SourceFlowAnchor> anchors,
            int maxCandidates
    ) {
        return discover(
                root,
                anchors,
                maxCandidates,
                DEFAULT_MAX_SCANNED_FILES,
                DEFAULT_MAX_FILE_SIZE_KB,
                false
        );
    }

    public DiscoveryResult discover(
            Path root,
            List<SourceFlowAnchor> anchors,
            int maxCandidates,
            int maxScannedFiles,
            int maxFileSizeKb,
            boolean includeTests
    ) {
        Map<String, JavaFileInfo> javaFiles = loadJavaFiles(
                root,
                Math.max(1, maxScannedFiles),
                Math.max(1, maxFileSizeKb) * 1024L,
                includeTests
        );
        Map<String, JavaFileInfo> byClass = new LinkedHashMap<>();
        javaFiles.values().forEach(file -> byClass.put(file.className(), file));

        Map<String, SourceCandidateFlowChainItem> chain = new LinkedHashMap<>();
        Map<String, SourceCandidateMethod> methods = new LinkedHashMap<>();
        List<SourceFlowAnchor> primaryAnchors = anchors.stream()
                .filter(SourceFlowAnchor::primary)
                .toList();
        List<SourceFlowAnchor> endpointAnchors = primaryAnchors.stream()
                .filter(anchor -> "ENDPOINT".equals(anchor.type()))
                .toList();
        List<ControllerEndpoint> discoveredEndpoints = javaFiles.values()
                .stream()
                .filter(this::isLikelyController)
                .flatMap(file -> controllerEndpoints(file).stream())
                .toList();
        Set<String> matchedEndpointAnchors = new LinkedHashSet<>();
        int endpointMatchAttempts = 0;

        if (!endpointAnchors.isEmpty()) {
            for (ControllerEndpoint endpoint : discoveredEndpoints) {
                for (SourceFlowAnchor anchor : endpointAnchors) {
                    endpointMatchAttempts++;
                    if (pathMatches(endpoint.fullPath(), anchor.value())) {
                        matchedEndpointAnchors.add(anchor.value());
                        addChain(chain, endpoint.file(), endpoint.method(),
                                List.of(anchor.value()),
                                "Controller endpoint mapping matched primary endpoint anchor.");
                        addMethod(methods, endpoint.file(), endpoint.method(),
                                List.of(anchor.value()));
                        expandFromMethod(
                                chain,
                                methods,
                                endpoint.file(),
                                endpoint.method(),
                                List.of(anchor.value()),
                                byClass
                        );
                    }
                }
            }

            return discoveryResult(
                    chain,
                    methods,
                    maxCandidates,
                    javaFiles,
                    discoveredEndpoints,
                    endpointAnchors,
                    matchedEndpointAnchors,
                    endpointMatchAttempts
            );
        }

        for (JavaFileInfo file : javaFiles.values()) {
            List<String> classSignals = relatedClassSignals(file, primaryAnchors);
            if (!classSignals.isEmpty()) {
                addChain(chain, file, null, classSignals,
                        "Class name or structural type matched flow anchor.");
            }

            for (JavaMethodInfo method : file.methods()) {
                List<String> methodSignals = relatedMethodSignals(
                        method,
                        primaryAnchors
                );
                if (!methodSignals.isEmpty()) {
                    addChain(chain, file, method, methodSignals,
                            "Method signature or bounded body matched flow anchor.");
                    addMethod(methods, file, method, methodSignals);
                }
            }
        }

        return discoveryResult(
                chain,
                methods,
                maxCandidates,
                javaFiles,
                discoveredEndpoints,
                endpointAnchors,
                matchedEndpointAnchors,
                endpointMatchAttempts
        );
    }

    private DiscoveryResult discoveryResult(
            Map<String, SourceCandidateFlowChainItem> chain,
            Map<String, SourceCandidateMethod> methods,
            int maxCandidates,
            Map<String, JavaFileInfo> javaFiles,
            List<ControllerEndpoint> discoveredEndpoints,
            List<SourceFlowAnchor> endpointAnchors,
            Set<String> matchedEndpointAnchors,
            int endpointMatchAttempts
    ) {
        List<SourceCandidateFlowChainItem> limitedChain = chain.values()
                .stream()
                .limit(maxCandidates)
                .toList();
        Set<String> candidateFiles = new LinkedHashSet<>();
        limitedChain.forEach(item -> candidateFiles.add(item.file()));

        return new DiscoveryResult(
                limitedChain,
                candidateFiles.stream().toList(),
                methods.values().stream()
                        .filter(method -> candidateFiles.contains(method.file()))
                        .limit(maxCandidates * 3L)
                        .toList(),
                javaFiles,
                (int) javaFiles.values().stream()
                        .filter(this::isLikelyController)
                        .count(),
                discoveredEndpoints.size(),
                endpointMatchAttempts,
                matchedEndpointAnchors.stream().toList(),
                endpointAnchors.stream()
                        .map(SourceFlowAnchor::value)
                        .filter(value -> !matchedEndpointAnchors.contains(value))
                        .toList(),
                discoveredEndpoints.stream()
                        .map(ControllerEndpoint::view)
                        .limit(10)
                        .toList()
        );
    }

    private void expandFromMethod(
            Map<String, SourceCandidateFlowChainItem> chain,
            Map<String, SourceCandidateMethod> methods,
            JavaFileInfo controller,
            JavaMethodInfo method,
            List<String> relatedSignals,
            Map<String, JavaFileInfo> byClass
    ) {
        for (Map.Entry<String, String> dependency : controller.fields().entrySet()) {
            if (!method.body().contains(dependency.getKey() + ".")) {
                continue;
            }

            JavaFileInfo dependencyFile = byClass.get(dependency.getValue());
            if (dependencyFile != null) {
                addChain(chain, dependencyFile, null, relatedSignals,
                        "Injected dependency called by controller method.");
                addCalledDependencyMethods(
                        chain,
                        methods,
                        dependencyFile,
                        dependency.getKey(),
                        method,
                        relatedSignals
                );
            }
        }

        Set<String> typeNames = methodSignatureTypes(method);
        for (String typeName : typeNames) {
            JavaFileInfo relatedFile = byClass.get(typeName);
            if (relatedFile != null) {
                addChain(chain, relatedFile, null, relatedSignals,
                        "DTO/entity/mapper/repository type referenced by method signature.");
            }
        }
    }

    private void addCalledDependencyMethods(
            Map<String, SourceCandidateFlowChainItem> chain,
            Map<String, SourceCandidateMethod> methods,
            JavaFileInfo dependencyFile,
            String variableName,
            JavaMethodInfo caller,
            List<String> relatedSignals
    ) {
        Matcher matcher = CALL_PATTERN.matcher(caller.body());
        while (matcher.find()) {
            if (!variableName.equals(matcher.group(1))) {
                continue;
            }
            String methodName = matcher.group(2);
            dependencyFile.methods()
                    .stream()
                    .filter(method -> methodName.equals(method.name()))
                    .findFirst()
                    .ifPresent(method -> {
                        addChain(chain, dependencyFile, method, relatedSignals,
                                "Direct service method call from controller flow.");
                        addMethod(methods, dependencyFile, method, relatedSignals);
                    });
        }
    }

    private List<String> relatedClassSignals(
            JavaFileInfo file,
            List<SourceFlowAnchor> anchors
    ) {
        List<String> signals = new ArrayList<>();
        for (SourceFlowAnchor anchor : anchors) {
            if ("ENDPOINT".equals(anchor.type())
                    || "BUSINESS_FLOW".equals(anchor.type())) {
                continue;
            }
            if (containsIgnoreCase(file.className(), anchor.value())) {
                signals.add(anchor.value());
            }
        }
        return signals;
    }

    private List<String> relatedEndpointSignals(
            List<String> annotations,
            List<SourceFlowAnchor> anchors
    ) {
        List<String> signals = new ArrayList<>();
        String annotationText = String.join(" ", annotations);
        for (SourceFlowAnchor anchor : anchors) {
            if ("ENDPOINT".equals(anchor.type())
                    && endpointMatches(annotationText, anchor.value())) {
                signals.add(anchor.value());
            }
        }
        return signals;
    }

    private List<String> relatedMethodSignals(
            JavaMethodInfo method,
            List<SourceFlowAnchor> anchors
    ) {
        List<String> signals = new ArrayList<>();
        String text = method.signature() + "\n" + method.body();
        for (SourceFlowAnchor anchor : anchors) {
            if ("ENDPOINT".equals(anchor.type())) {
                continue;
            }
            if ("CONSTANT".equals(anchor.type())) {
                if (text.contains(anchor.value())) {
                    signals.add(anchor.value());
                }
                continue;
            }
            if (containsIgnoreCase(text, anchor.value())) {
                signals.add(anchor.value());
            }
        }
        return signals;
    }

    private boolean endpointMatches(String annotationText, String endpoint) {
        return annotationText.contains(endpoint)
                || annotationText.replace("\" + \"", "")
                .contains(endpoint);
    }

    private List<ControllerEndpoint> controllerEndpoints(JavaFileInfo file) {
        List<PathMapping> classMappings = mappings(file.mappingAnnotations(),
                file.constants());
        if (classMappings.isEmpty()) {
            classMappings = List.of(new PathMapping("", "", ""));
        }

        List<ControllerEndpoint> endpoints = new ArrayList<>();
        for (JavaMethodInfo method : file.methods()) {
            List<PathMapping> methodMappings = mappings(
                    method.annotations(),
                    file.constants()
            );
            if (methodMappings.isEmpty()) {
                continue;
            }
            for (PathMapping classMapping : classMappings) {
                for (PathMapping methodMapping : methodMappings) {
                    String fullPath = combinePaths(
                            classMapping.path(),
                            methodMapping.path()
                    );
                    endpoints.add(new ControllerEndpoint(
                            file,
                            method,
                            methodMapping.httpMethod(),
                            classMapping.path(),
                            methodMapping.path(),
                            fullPath
                    ));
                }
            }
        }
        return endpoints;
    }

    private List<PathMapping> mappings(
            List<String> annotations,
            Map<String, String> constants
    ) {
        List<PathMapping> values = new ArrayList<>();
        for (String annotation : annotations) {
            String httpMethod = httpMethod(annotation);
            if (httpMethod == null) {
                continue;
            }
            List<String> paths = paths(annotation, constants);
            for (String path : paths) {
                values.add(new PathMapping(httpMethod, path, normalizePath(path)));
            }
        }
        return values;
    }

    private List<String> paths(String annotation, Map<String, String> constants) {
        List<String> values = new ArrayList<>();
        Matcher literalMatcher = STRING_LITERAL_PATTERN.matcher(annotation);
        while (literalMatcher.find()) {
            values.add(literalMatcher.group(1));
        }
        for (Map.Entry<String, String> constant : constants.entrySet()) {
            if (annotation.matches(".*\\b" + Pattern.quote(constant.getKey())
                    + "\\b.*")) {
                values.add(constant.getValue());
            }
        }
        if (values.isEmpty()) {
            values.add("");
        }
        return values.stream()
                .map(this::normalizePath)
                .distinct()
                .toList();
    }

    private String httpMethod(String annotation) {
        if (annotation.startsWith("@GetMapping")) {
            return "GET";
        }
        if (annotation.startsWith("@PostMapping")) {
            return "POST";
        }
        if (annotation.startsWith("@PutMapping")) {
            return "PUT";
        }
        if (annotation.startsWith("@PatchMapping")) {
            return "PATCH";
        }
        if (annotation.startsWith("@DeleteMapping")) {
            return "DELETE";
        }
        if (annotation.startsWith("@RequestMapping")) {
            String upper = annotation.toUpperCase(Locale.ROOT);
            if (upper.contains("REQUESTMETHOD.GET")) {
                return "GET";
            }
            if (upper.contains("REQUESTMETHOD.POST")) {
                return "POST";
            }
            if (upper.contains("REQUESTMETHOD.PUT")) {
                return "PUT";
            }
            if (upper.contains("REQUESTMETHOD.PATCH")) {
                return "PATCH";
            }
            if (upper.contains("REQUESTMETHOD.DELETE")) {
                return "DELETE";
            }
            return "REQUEST";
        }
        return null;
    }

    private String combinePaths(String classPath, String methodPath) {
        return normalizePath(normalizePath(classPath) + "/" + normalizePath(methodPath));
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/')
                .replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean pathMatches(String discoveredPath, String anchorPath) {
        String left = normalizePath(discoveredPath);
        String right = normalizePath(anchorPath);
        if (left.equals(right)) {
            return true;
        }
        String[] leftParts = left.substring(1).split("/");
        String[] rightParts = right.substring(1).split("/");
        if (leftParts.length != rightParts.length) {
            return false;
        }
        for (int index = 0; index < leftParts.length; index++) {
            if (leftParts[index].startsWith("{") && leftParts[index].endsWith("}")) {
                continue;
            }
            if (!leftParts[index].equals(rightParts[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean isLikelyController(JavaFileInfo file) {
        String path = file.relativePath().toLowerCase(Locale.ROOT);
        return path.contains("/api/")
                || path.contains("/controller/")
                || file.relativePath().endsWith("Controller.java")
                || file.relativePath().endsWith("Resource.java")
                || file.relativePath().endsWith("Endpoint.java")
                || !file.mappingAnnotations().isEmpty();
    }

    private boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(Locale.ROOT)
                .contains(value.toLowerCase(Locale.ROOT));
    }

    private void addChain(
            Map<String, SourceCandidateFlowChainItem> chain,
            JavaFileInfo file,
            JavaMethodInfo method,
            List<String> relatedSignals,
            String reason
    ) {
        String key = file.relativePath() + "#"
                + (method == null ? "" : method.name())
                + "#" + reason;
        chain.putIfAbsent(
                key,
                new SourceCandidateFlowChainItem(
                        layer(file),
                        file.relativePath(),
                        file.className(),
                        method == null ? null : method.name(),
                        List.copyOf(new LinkedHashSet<>(relatedSignals)),
                        reason,
                        "HYPOTHESIS"
                )
        );
    }

    private void addMethod(
            Map<String, SourceCandidateMethod> methods,
            JavaFileInfo file,
            JavaMethodInfo method,
            List<String> relatedSignals
    ) {
        String key = file.relativePath() + "#" + method.name();
        methods.putIfAbsent(
                key,
                new SourceCandidateMethod(
                        file.relativePath(),
                        file.className(),
                        method.name(),
                        method.startLine(),
                        method.endLine(),
                        List.copyOf(new LinkedHashSet<>(relatedSignals)),
                        snippet(method)
                )
        );
    }

    private String snippet(JavaMethodInfo method) {
        String value = method.signature() + "\n" + method.body();
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private Set<String> methodSignatureTypes(JavaMethodInfo method) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = TYPE_PATTERN.matcher(method.signature());
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String layer(JavaFileInfo file) {
        String className = file.className();
        if (file.mappingAnnotations().isEmpty()
                && className.endsWith("Controller")) {
            return "CONTROLLER";
        }
        if (!file.mappingAnnotations().isEmpty()) {
            return "CONTROLLER";
        }
        if (className.endsWith("Service") || className.endsWith("ServiceImpl")) {
            return "SERVICE";
        }
        if (className.endsWith("Repository")) {
            return "REPOSITORY";
        }
        if (className.endsWith("Mapper")) {
            return "MAPPER";
        }
        if (className.endsWith("Validator")) {
            return "VALIDATOR";
        }
        if (className.endsWith("Entity")) {
            return "ENTITY";
        }
        if (className.endsWith("Dto") || className.endsWith("DTO")
                || className.endsWith("Request")
                || className.endsWith("Response")) {
            return "DTO";
        }
        if (className.endsWith("Config") || className.endsWith("Configuration")) {
            return "CONFIG";
        }
        return "UNKNOWN";
    }

    private Map<String, JavaFileInfo> loadJavaFiles(
            Path root,
            int maxScannedFiles,
            long maxFileSizeBytes,
            boolean includeTests
    ) {
        Map<String, JavaFileInfo> files = new LinkedHashMap<>();
        List<Path> roots = scanRoots(root);
        List<Path> controllerPaths = new ArrayList<>();
        List<Path> fallbackPaths = new ArrayList<>();
        for (Path scanRoot : roots) {
            try (Stream<Path> stream = Files.walk(scanRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(root, path, includeTests))
                        .filter(path -> isSupported(path)
                                && path.toString().endsWith(".java"))
                        .sorted(sourcePathComparator())
                        .forEach(path -> {
                            if (controllerPriority(path.toString()) == 0) {
                                controllerPaths.add(path);
                            } else if (fallbackPaths.size() < maxScannedFiles) {
                                fallbackPaths.add(path);
                            }
                        });
            } catch (Exception ignored) {
                continue;
            }
            if (controllerPaths.size() >= maxScannedFiles) {
                break;
            }
        }
        List<Path> paths = new ArrayList<>();
        for (Path path : controllerPaths) {
            if (paths.size() >= maxScannedFiles) {
                break;
            }
            paths.add(path);
        }
        for (Path path : fallbackPaths) {
            if (paths.size() >= maxScannedFiles) {
                break;
            }
            paths.add(path);
        }
        for (Path path : paths) {
            parseJava(root, path, maxFileSizeBytes)
                    .ifPresent(file -> files.put(file.relativePath(), file));
        }
        return files;
    }

    private List<Path> scanRoots(Path root) {
        Set<Path> roots = new LinkedHashSet<>();
        for (String directory : PREFERRED_SOURCE_DIRECTORIES) {
            Path candidate = root.resolve(directory);
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !isExcludedDirectoryName(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(roots::add);
        } catch (Exception ignored) {
            // Fall through to the repository root when child directory listing fails.
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return roots.stream().toList();
    }

    private boolean isExcludedDirectoryName(Path path) {
        String value = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXCLUDED_DIRECTORIES.contains(value) || value.startsWith(".");
    }

    private Optional<JavaFileInfo> parseJava(
            Path root,
            Path path,
            long maxFileSizeBytes
    ) {
        try {
            if (Files.size(path) > maxFileSizeBytes) {
                return Optional.empty();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher classMatcher = CLASS_PATTERN.matcher(content);
            if (!classMatcher.find()) {
                return Optional.empty();
            }
            String relativePath = root.relativize(path)
                    .toString()
                    .replace('\\', '/');
            String className = classMatcher.group(1);
            return Optional.of(new JavaFileInfo(
                    relativePath,
                    className,
                    imports(content),
                    fields(content),
                    constants(content),
                    classMappingAnnotations(content),
                    methods(content)
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Comparator<Path> sourcePathComparator() {
        return Comparator
                .comparing((Path path) -> controllerPriority(path.toString()))
                .thenComparing(Path::toString);
    }

    private int controllerPriority(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/api/")
                || normalized.contains("/controller/")
                || normalized.endsWith("controller.java")
                || normalized.endsWith("resource.java")
                || normalized.endsWith("endpoint.java")) {
            return 0;
        }
        return 1;
    }

    private List<String> imports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    private Map<String, String> fields(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD_PATTERN.matcher(content);
        while (matcher.find()) {
            fields.put(matcher.group(2), matcher.group(1));
        }
        return fields;
    }

    private Map<String, String> constants(String content) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher matcher = STRING_CONSTANT_PATTERN.matcher(content);
        while (matcher.find()) {
            constants.put(matcher.group(1), matcher.group(2));
        }
        return constants;
    }

    private List<String> classMappingAnnotations(String content) {
        int classIndex = content.indexOf(" class ");
        String prefix = classIndex < 0 ? content : content.substring(0, classIndex);
        return mappingAnnotations(prefix);
    }

    private List<JavaMethodInfo> methods(String content) {
        String[] lines = content.split("\\R", -1);
        List<JavaMethodInfo> methods = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();

        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("@")) {
                pendingAnnotations.add(trimmed);
                continue;
            }

            Matcher matcher = METHOD_PATTERN.matcher(lines[index]);
            if (matcher.matches()) {
                String name = matcher.group(2);
                int start = index + 1;
                int end = findMethodEndLine(lines, index);
                String body = linesBetween(lines, start, end);
                methods.add(new JavaMethodInfo(
                        name,
                        start,
                        end,
                        lines[index].trim(),
                        List.copyOf(mappingAnnotations(pendingAnnotations)),
                        body
                ));
                pendingAnnotations.clear();
                continue;
            }

            if (!trimmed.isBlank() && !trimmed.startsWith("//")) {
                pendingAnnotations.clear();
            }
        }
        return methods;
    }

    private List<String> mappingAnnotations(List<String> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.contains("Mapping"))
                .toList();
    }

    private List<String> mappingAnnotations(String text) {
        List<String> annotations = new ArrayList<>();
        Matcher matcher = MAPPING_PATTERN.matcher(text);
        while (matcher.find()) {
            annotations.add(matcher.group());
        }
        return annotations;
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

    private String linesBetween(String[] lines, int startLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int index = startLine - 1; index < endLine && index < lines.length; index++) {
            builder.append(lines[index]).append('\n');
        }
        return builder.toString();
    }

    private boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private boolean isExcluded(Path root, Path path, boolean includeTests) {
        Path relative = root.relativize(path);
        String relativeValue = relative.toString().replace('\\', '/');
        if (!includeTests
                && (relativeValue.startsWith("src/test/")
                || relativeValue.contains("/src/test/"))) {
            return true;
        }
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

    public record DiscoveryResult(
            List<SourceCandidateFlowChainItem> candidateFlowChain,
            List<String> candidateFiles,
            List<SourceCandidateMethod> candidateMethods,
            Map<String, JavaFileInfo> javaFiles,
            int endpointSearchFileCount,
            int controllerCandidateCount,
            int endpointMatchAttempts,
            List<String> matchedEndpointAnchors,
            List<String> unmatchedEndpointAnchors,
            List<SourceDiscoveredControllerEndpoint> discoveredControllerEndpoints
    ) {
        public DiscoveryResult(
                List<SourceCandidateFlowChainItem> candidateFlowChain,
                List<String> candidateFiles,
                List<SourceCandidateMethod> candidateMethods,
                Map<String, JavaFileInfo> javaFiles
        ) {
            this(
                    candidateFlowChain,
                    candidateFiles,
                    candidateMethods,
                    javaFiles,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    public record JavaFileInfo(
            String relativePath,
            String className,
            List<String> imports,
            Map<String, String> fields,
            Map<String, String> constants,
            List<String> mappingAnnotations,
            List<JavaMethodInfo> methods
    ) {
        public JavaFileInfo(
                String relativePath,
                String className,
                List<String> imports,
                Map<String, String> fields,
                List<String> mappingAnnotations,
                List<JavaMethodInfo> methods
        ) {
            this(
                    relativePath,
                    className,
                    imports,
                    fields,
                    Map.of(),
                    mappingAnnotations,
                    methods
            );
        }
    }

    public record JavaMethodInfo(
            String name,
            int startLine,
            int endLine,
            String signature,
            List<String> annotations,
            String body
    ) {
    }

    private record PathMapping(
            String httpMethod,
            String rawPath,
            String path
    ) {
    }

    private record ControllerEndpoint(
            JavaFileInfo file,
            JavaMethodInfo method,
            String httpMethod,
            String classPath,
            String methodPath,
            String fullPath
    ) {
        private SourceDiscoveredControllerEndpoint view() {
            return new SourceDiscoveredControllerEndpoint(
                    file.relativePath(),
                    file.className(),
                    method.name(),
                    httpMethod,
                    classPath,
                    methodPath,
                    fullPath
            );
        }
    }
}

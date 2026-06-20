package com.etiya.replayfix.service;

import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
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

        for (JavaFileInfo file : javaFiles.values()) {
            List<String> classSignals = relatedClassSignals(file, anchors);
            if (!classSignals.isEmpty()) {
                addChain(chain, file, null, classSignals,
                        "Class name or structural type matched flow anchor.");
            }

            for (JavaMethodInfo method : file.methods()) {
                List<String> endpointSignals =
                        relatedEndpointSignals(method.annotations(), anchors);
                if (!endpointSignals.isEmpty()) {
                    addChain(chain, file, method, endpointSignals,
                            "Controller mapping annotation matched endpoint anchor.");
                    addMethod(methods, file, method, endpointSignals);
                    expandFromMethod(chain, methods, file, method,
                            endpointSignals, byClass);
                    continue;
                }

                List<String> methodSignals = relatedMethodSignals(
                        method,
                        anchors
                );
                if (!methodSignals.isEmpty()) {
                    addChain(chain, file, method, methodSignals,
                            "Method signature or bounded body matched flow anchor.");
                    addMethod(methods, file, method, methodSignals);
                }
            }
        }

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
                javaFiles
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
        List<Path> paths = new ArrayList<>();
        for (Path scanRoot : roots) {
            try (Stream<Path> stream = Files.walk(scanRoot)) {
                List<Path> discovered = stream.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(root, path, includeTests))
                        .filter(path -> isSupported(path)
                                && path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path path : discovered) {
                    if (paths.size() >= maxScannedFiles) {
                        break;
                    }
                    paths.add(path);
                }
            } catch (Exception ignored) {
                continue;
            }
            if (paths.size() >= maxScannedFiles) {
                break;
            }
        }
        for (Path path : paths) {
            parseJava(root, path, maxFileSizeBytes)
                    .ifPresent(file -> files.put(file.relativePath(), file));
        }
        return files;
    }

    private List<Path> scanRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        for (String directory : PREFERRED_SOURCE_DIRECTORIES) {
            Path candidate = root.resolve(directory);
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return roots;
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
                    classMappingAnnotations(content),
                    methods(content)
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
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
            Map<String, JavaFileInfo> javaFiles
    ) {
    }

    public record JavaFileInfo(
            String relativePath,
            String className,
            List<String> imports,
            Map<String, String> fields,
            List<String> mappingAnnotations,
            List<JavaMethodInfo> methods
    ) {
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
}

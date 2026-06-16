package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ExistingTestPatternDiscoveryService {

    private static final String PLAN_SOURCE = "regression-test-plan";
    private static final String WRITE_RESULT_SOURCE = "approved-generated-test-write-result";
    private static final String SELECTION_SOURCE = "existing-test-pattern-selection";

    private static final int MAX_FILES = 3000;
    private static final int MAX_FILE_SIZE = 500 * 1024;
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_ALTERNATIVES = 4;
    private static final int MAX_EXCERPT_LENGTH = 6000;

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "target", "build", "node_modules", "generated"
    );

    private final EvidenceService evidenceService;
    private final EvidenceSanitizer evidenceSanitizer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ExistingTestPatternDiscoveryService(
            EvidenceService evidenceService,
            EvidenceSanitizer evidenceSanitizer,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.evidenceSanitizer = evidenceSanitizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TestPatternDiscoveryResult discover(UUID caseId) {
        EvidenceEntity planEvidence = latestRequired(
                caseId,
                EvidenceType.GENERATED_TEST,
                PLAN_SOURCE
        );

        RegressionTestPlan plan = parse(planEvidence, RegressionTestPlan.class);

        EvidenceEntity writeResultEvidence = latestRequired(
                caseId,
                EvidenceType.GENERATED_TEST,
                WRITE_RESULT_SOURCE
        );

        GeneratedTestWriteResult writeResult = parse(
                writeResultEvidence,
                GeneratedTestWriteResult.class
        );

        Path workspace = Path.of(writeResult.workspace())
                .toAbsolutePath()
                .normalize();

        List<String> searchTerms = buildSearchTerms(plan);

        List<TestPatternCandidate> candidates = scanWorkspace(
                workspace,
                plan,
                writeResult,
                searchTerms
        );

        List<String> warnings = new ArrayList<>();

        if (candidates.isEmpty()) {
            warnings.add("No suitable test patterns found in repository.");
        }

        candidates.sort(Comparator.comparingInt(TestPatternCandidate::score).reversed());

        TestPatternCandidate selected = candidates.isEmpty()
                ? createFallbackCandidate(plan)
                : candidates.get(0);

        List<TestPatternCandidate> alternatives = candidates.stream()
                .skip(1)
                .limit(MAX_ALTERNATIVES)
                .toList();

        ExistingTestPatternSelection selection = new ExistingTestPatternSelection(
                caseId,
                plan.repositorySlug(),
                workspace.toString(),
                plan.targetProductionClass(),
                plan.targetProductionMethod(),
                extractPackage(plan.proposedFilePath()),
                candidates.size() + (candidates.size() < MAX_FILES ? 0 : 1),
                candidates.size(),
                selected,
                alternatives,
                searchTerms,
                warnings
        );

        saveEvidence(caseId, selection);

        auditService.record(
                caseId,
                "EXISTING_TEST_PATTERN_SELECTED",
                "system",
                "selected="
                        + selected.relativePath()
                        + ", score="
                        + selected.score()
                        + ", alternatives="
                        + alternatives.size()
        );

        return new TestPatternDiscoveryResult(
                caseId,
                "GENERATED_TEST",
                SELECTION_SOURCE,
                selection,
                false,
                false
        );
    }

    private List<TestPatternCandidate> scanWorkspace(
            Path workspace,
            RegressionTestPlan plan,
            GeneratedTestWriteResult writeResult,
            List<String> searchTerms
    ) {
        List<TestPatternCandidate> candidates = new ArrayList<>();

        Path testDir = workspace.resolve("src/test/java");

        if (!Files.isDirectory(testDir)) {
            return candidates;
        }

        try (Stream<Path> paths = Files.walk(testDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isExcluded(p, workspace))
                    .filter(p -> !isReplayFixGenerated(p, writeResult))
                    .limit(MAX_FILES)
                    .forEach(path -> {
                        try {
                            if (Files.size(path) > MAX_FILE_SIZE) {
                                return;
                            }

                            String content = Files.readString(path);
                            TestPatternCandidate candidate = analyzeTestFile(
                                    workspace,
                                    path,
                                    content,
                                    plan,
                                    searchTerms
                            );

                            if (candidate != null) {
                                candidates.add(candidate);
                            }

                        } catch (Exception ignored) {
                        }
                    });

        } catch (IOException ignored) {
        }

        return candidates.stream()
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private TestPatternCandidate analyzeTestFile(
            Path workspace,
            Path testFile,
            String content,
            RegressionTestPlan plan,
            List<String> searchTerms
    ) {
        String relativePath = workspace.relativize(testFile)
                .toString()
                .replace('\\', '/');

        String packageName = extractPackageFromContent(content);
        String className = testFile.getFileName()
                .toString()
                .replace(".java", "");

        List<String> imports = extractImports(content);
        List<String> annotations = extractAnnotations(content);
        List<String> testMethods = extractTestMethods(content);
        List<String> mockedTypes = extractMockedTypes(content);

        String framework = detectFramework(imports, annotations);
        String testStyle = detectTestStyle(imports, annotations);

        int score = calculateScore(
                packageName,
                className,
                relativePath,
                content,
                framework,
                testStyle,
                testMethods,
                plan,
                searchTerms
        );

        List<String> reasons = buildReasons(
                packageName,
                className,
                content,
                framework,
                testStyle,
                testMethods,
                plan
        );

        String excerpt = truncate(
                evidenceSanitizer.sanitize(content),
                MAX_EXCERPT_LENGTH
        );

        return new TestPatternCandidate(
                relativePath,
                packageName,
                className,
                framework,
                testStyle,
                score,
                reasons,
                truncateList(imports, 50),
                truncateList(annotations, 30),
                truncateList(testMethods, 20),
                truncateList(mockedTypes, 20),
                excerpt
        );
    }

    private int calculateScore(
            String packageName,
            String className,
            String relativePath,
            String content,
            String framework,
            String testStyle,
            List<String> testMethods,
            RegressionTestPlan plan,
            List<String> searchTerms
    ) {
        int score = 0;

        if (testMethods.isEmpty()) {
            score -= 100;
        }

        String targetPackage = extractPackage(plan.proposedFilePath());

        if (packageName.equals(targetPackage)) {
            score += 100;
        } else if (isParentPackage(packageName, targetPackage)) {
            score += 35;
        }

        if (content.contains(plan.targetProductionClass())) {
            score += 120;
        }

        if (content.contains(plan.targetProductionMethod())) {
            score += 80;
        }

        if (framework.contains(plan.framework())) {
            score += 40;
        }

        String planType = plan.testType() == null ? "" : plan.testType().toLowerCase();

        if (planType.contains("unit") && testStyle.equals("MOCKITO_UNIT")) {
            score += 40;
        } else if (planType.contains("integration") && testStyle.equals("SPRING_BOOT_INTEGRATION")) {
            score += 40;
        }

        for (String term : searchTerms) {
            if (relativePath.toLowerCase().contains(term.toLowerCase())) {
                score += 25;
            }

            if (content.toLowerCase().contains(term.toLowerCase())) {
                score += 10;
            }
        }

        if (className.endsWith("Test") || className.endsWith("Tests") || className.endsWith("IT")) {
            score += 10;
        }

        return score;
    }

    private List<String> buildReasons(
            String packageName,
            String className,
            String content,
            String framework,
            String testStyle,
            List<String> testMethods,
            RegressionTestPlan plan
    ) {
        List<String> reasons = new ArrayList<>();

        String targetPackage = extractPackage(plan.proposedFilePath());

        if (packageName.equals(targetPackage)) {
            reasons.add("Same package as proposed regression test.");
        }

        if (content.contains(plan.targetProductionClass())) {
            reasons.add("References target production class.");
        }

        if (content.contains(plan.targetProductionMethod())) {
            reasons.add("References target production method.");
        }

        if (framework.contains(plan.framework())) {
            reasons.add("Framework matches plan.");
        }

        if (!testMethods.isEmpty()) {
            reasons.add("Contains " + testMethods.size() + " test methods.");
        }

        if (testStyle.equals("MOCKITO_UNIT")) {
            reasons.add("Uses Mockito unit test style.");
        } else if (testStyle.equals("SPRING_BOOT_INTEGRATION")) {
            reasons.add("Uses Spring Boot integration test style.");
        }

        return reasons;
    }

    private List<String> buildSearchTerms(RegressionTestPlan plan) {
        Set<String> terms = new HashSet<>();

        addTerm(terms, plan.targetProductionClass());
        addTerm(terms, plan.targetProductionMethod());
        addTerm(terms, plan.repositorySlug());
        addTerm(terms, plan.testType());

        if (plan.mocks() != null) {
            plan.mocks().forEach(mock -> addTerm(terms, mock));
        }

        if (plan.requestData() != null) {
            plan.requestData().forEach((key, value) -> {
                addTerm(terms, key);
                addTerm(terms, value);
            });
        }

        return terms.stream()
                .filter(t -> t.length() >= 3 && t.length() <= 50)
                .limit(20)
                .toList();
    }

    private void addTerm(Set<String> terms, String value) {
        if (value != null && !value.isBlank()) {
            terms.add(value.trim());
        }
    }

    private String extractPackageFromContent(String content) {
        Pattern pattern = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;");
        Matcher matcher = pattern.matcher(content);

        return matcher.find() ? matcher.group(1) : "";
    }

    private List<String> extractImports(String content) {
        List<String> imports = new ArrayList<>();
        Pattern pattern = Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z0-9_.$]+)\\s*;");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            imports.add(matcher.group(1));
        }

        return imports;
    }

    private List<String> extractAnnotations(String content) {
        Set<String> annotations = new HashSet<>();
        Pattern pattern = Pattern.compile("@([A-Za-z0-9_]+)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            annotations.add("@" + matcher.group(1));
        }

        return new ArrayList<>(annotations);
    }

    private List<String> extractTestMethods(String content) {
        List<String> methods = new ArrayList<>();
        Pattern pattern = Pattern.compile("@Test[^{]*?\\s+(public|private|protected)?\\s*void\\s+([a-zA-Z0-9_]+)\\s*\\(");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            methods.add(matcher.group(2));
        }

        return methods;
    }

    private List<String> extractMockedTypes(String content) {
        Set<String> mockedTypes = new HashSet<>();

        Pattern mockPattern = Pattern.compile("@(?:Mock|MockBean|Spy|SpyBean)\\s+(?:private\\s+)?([A-Za-z0-9_<>]+)");
        Matcher matcher = mockPattern.matcher(content);

        while (matcher.find()) {
            mockedTypes.add(matcher.group(1));
        }

        return new ArrayList<>(mockedTypes);
    }

    private String detectFramework(List<String> imports, List<String> annotations) {
        boolean hasJUnit5 = imports.stream()
                .anyMatch(i -> i.contains("org.junit.jupiter"));

        boolean hasJUnit4 = imports.stream()
                .anyMatch(i -> i.contains("org.junit.Test"));

        boolean hasMockito = imports.stream()
                .anyMatch(i -> i.contains("org.mockito"));

        boolean hasSpringBootTest = annotations.stream()
                .anyMatch(a -> a.equals("@SpringBootTest"));

        if (hasJUnit5 && hasSpringBootTest) {
            return "JUnit 5 + Spring Boot Test";
        } else if (hasJUnit5 && hasMockito) {
            return "JUnit 5 + Mockito";
        } else if (hasJUnit5) {
            return "JUnit 5";
        } else if (hasJUnit4) {
            return "JUnit 4";
        }

        return "UNKNOWN";
    }

    private String detectTestStyle(List<String> imports, List<String> annotations) {
        boolean hasMockito = imports.stream()
                .anyMatch(i -> i.contains("org.mockito"));

        boolean hasSpringBootTest = annotations.stream()
                .anyMatch(a -> a.equals("@SpringBootTest"));

        if (hasSpringBootTest) {
            return "SPRING_BOOT_INTEGRATION";
        } else if (hasMockito) {
            return "MOCKITO_UNIT";
        }

        return "PLAIN_JUNIT";
    }

    private String extractPackage(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String prefix = "src/test/java/";

        if (!normalized.contains(prefix)) {
            return "";
        }

        int startIndex = normalized.indexOf(prefix) + prefix.length();
        int endIndex = normalized.lastIndexOf('/');

        if (endIndex <= startIndex) {
            return "";
        }

        return normalized.substring(startIndex, endIndex)
                .replace('/', '.');
    }

    private boolean isParentPackage(String testPackage, String targetPackage) {
        if (testPackage.isEmpty() || targetPackage.isEmpty()) {
            return false;
        }

        return targetPackage.startsWith(testPackage + ".")
                || testPackage.startsWith(targetPackage + ".");
    }

    private boolean isExcluded(Path path, Path workspace) {
        Path relativePath = workspace.relativize(path);

        for (String excluded : EXCLUDED_DIRS) {
            if (relativePath.toString().contains(excluded)) {
                return true;
            }
        }

        return false;
    }

    private boolean isReplayFixGenerated(Path path, GeneratedTestWriteResult writeResult) {
        String pathStr = path.toString().replace('\\', '/');
        String generatedPath = writeResult.relativePath().replace('\\', '/');

        return pathStr.endsWith(generatedPath)
                || pathStr.contains("ReplayFixRegressionTest");
    }

    private TestPatternCandidate createFallbackCandidate(RegressionTestPlan plan) {
        return new TestPatternCandidate(
                "src/test/java/FallbackPattern.java",
                "",
                "FallbackPattern",
                plan.framework(),
                "PLAIN_JUNIT",
                0,
                List.of("No existing test patterns found in repository."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private void saveEvidence(UUID caseId, ExistingTestPatternSelection selection) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    SELECTION_SOURCE,
                    objectMapper.writeValueAsString(selection),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save test pattern selection evidence.",
                    exception
            );
        }
    }

    private EvidenceEntity latestRequired(
            UUID caseId,
            EvidenceType type,
            String source
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item -> item.getEvidenceType() == type)
                .filter(item -> source.equals(item.getSource()))
                .reduce((first, second) -> second)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Required evidence not found. type="
                                        + type
                                        + ", source="
                                        + source
                        )
                );
    }

    private <T> T parse(EvidenceEntity evidence, Class<T> type) {
        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    type
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse evidence. type="
                            + evidence.getEvidenceType()
                            + ", source="
                            + evidence.getSource(),
                    exception
            );
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }

        return value.substring(0, maxLength);
    }

    private <T> List<T> truncateList(List<T> list, int maxSize) {
        if (list == null || list.size() <= maxSize) {
            return list == null ? List.of() : list;
        }

        return list.subList(0, maxSize);
    }
}

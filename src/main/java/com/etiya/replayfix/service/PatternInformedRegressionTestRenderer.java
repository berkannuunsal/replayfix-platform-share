package com.etiya.replayfix.service;

import com.etiya.replayfix.model.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class PatternInformedRegressionTestRenderer {

    public PatternInformedTestSourceCandidate render(
            UUID caseId,
            RegressionTestPlan plan,
            ExistingTestPatternSelection patternSelection,
            JavaSourceSignatureAnalysis sourceAnalysis
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> unresolvedSymbols = new ArrayList<>();
        
        warnings.addAll(sourceAnalysis.warnings());

        TestPatternCandidate selected = patternSelection.selected();

        if (selected == null || selected.score() == 0) {
            warnings.add("No suitable test pattern found; candidate may not compile.");
            return createInsufficientContextCandidate(
                    caseId,
                    plan,
                    warnings,
                    assumptions,
                    unresolvedSymbols
            );
        }

        String packageName = resolvePackage(
                selected,
                sourceAnalysis,
                plan
        );

        String framework = selected.framework();
        String testStyle = selected.testStyle();

        List<String> reusedImports = buildImports(
                selected,
                sourceAnalysis,
                packageName,
                plan,
                warnings
        );

        List<String> reusedAnnotations = selected.annotations();

        String source = generateSource(
                packageName,
                plan,
                selected,
                sourceAnalysis,
                reusedImports,
                testStyle,
                warnings,
                assumptions,
                unresolvedSymbols
        );

        String hash = calculateSha256(source);

        List<String> detectedDependencies = extractDependencies(
                sourceAnalysis,
                selected
        );

        double confidence = calculateConfidence(
                selected,
                sourceAnalysis,
                packageName,
                plan,
                unresolvedSymbols
        );

        TestSourceReadiness readiness = determineReadiness(
                source,
                selected,
                unresolvedSymbols
        );

        String relativePath = normalizeRelativePath(plan.proposedFilePath());

        return new PatternInformedTestSourceCandidate(
                caseId,
                plan.repositorySlug(),
                plan.sourceCommitSha(),
                selected.relativePath(),
                plan.targetProductionClass(),
                plan.targetProductionMethod(),
                packageName,
                plan.proposedTestClass(),
                plan.proposedTestMethod(),
                relativePath,
                framework,
                testStyle,
                source,
                hash,
                readiness,
                confidence,
                reusedImports,
                reusedAnnotations,
                detectedDependencies,
                unresolvedSymbols,
                assumptions,
                warnings,
                false,
                false,
                true
        );
    }

    private String generateSource(
            String packageName,
            RegressionTestPlan plan,
            TestPatternCandidate selected,
            JavaSourceSignatureAnalysis sourceAnalysis,
            List<String> imports,
            String testStyle,
            List<String> warnings,
            List<String> assumptions,
            List<String> unresolvedSymbols
    ) {
        StringBuilder source = new StringBuilder();

        if (!packageName.isBlank()) {
            source.append("package ").append(packageName).append(";\n\n");
        }

        for (String imp : imports) {
            if (imp.contains("*")) {
                warnings.add("Wildcard import retained: " + imp);
            }
            source.append("import ").append(imp).append(";\n");
        }

        source.append("\n");

        appendClassAnnotations(source, selected, testStyle);

        source.append("class ").append(plan.proposedTestClass()).append(" {\n\n");

        appendMockFields(
                source,
                sourceAnalysis,
                selected,
                testStyle,
                unresolvedSymbols
        );

        source.append("    @Test\n");
        source.append("    @DisplayName(\"")
                .append(escapeJava(truncate(plan.scenario(), 180)))
                .append("\")\n");
        source.append("    void ").append(plan.proposedTestMethod()).append("() {\n");

        appendTestBody(
                source,
                plan,
                sourceAnalysis,
                testStyle,
                assumptions,
                unresolvedSymbols
        );

        source.append("    }\n");
        source.append("}\n");

        return source.toString();
    }

    private void appendClassAnnotations(
            StringBuilder source,
            TestPatternCandidate selected,
            String testStyle
    ) {
        if (testStyle.equals("MOCKITO_UNIT")
                && selected.annotations().contains("@ExtendWith")) {
            source.append("@ExtendWith(MockitoExtension.class)\n");
        } else if (testStyle.equals("SPRING_BOOT_INTEGRATION")
                && selected.annotations().contains("@SpringBootTest")) {
            source.append("@SpringBootTest\n");
        }
    }

    private void appendMockFields(
            StringBuilder source,
            JavaSourceSignatureAnalysis sourceAnalysis,
            TestPatternCandidate selected,
            String testStyle,
            List<String> unresolvedSymbols
    ) {
        if (!testStyle.equals("MOCKITO_UNIT")) {
            return;
        }

        JavaConstructorSignature constructor = sourceAnalysis.constructor();

        if (constructor != null && !constructor.parameters().isEmpty()) {
            for (JavaParameterSignature param : constructor.parameters()) {
                source.append("    @Mock\n");
                source.append("    private ").append(param.type())
                        .append(" ").append(param.name()).append(";\n\n");
            }
        } else if (!sourceAnalysis.fieldTypes().isEmpty()) {
            for (String fieldType : sourceAnalysis.fieldTypes()) {
                String fieldName = toLowerCamelCase(fieldType);
                source.append("    @Mock\n");
                source.append("    private ").append(fieldType)
                        .append(" ").append(fieldName).append(";\n\n");
            }
        }

        if (selected.annotations().contains("@InjectMocks")) {
            source.append("    @InjectMocks\n");
            source.append("    private ").append(sourceAnalysis.className())
                    .append(" ").append(toLowerCamelCase(sourceAnalysis.className()))
                    .append(";\n\n");
        } else {
            unresolvedSymbols.add("Manual " + sourceAnalysis.className()
                    + " initialization required");
        }
    }

    private void appendTestBody(
            StringBuilder source,
            RegressionTestPlan plan,
            JavaSourceSignatureAnalysis sourceAnalysis,
            String testStyle,
            List<String> assumptions,
            List<String> unresolvedSymbols
    ) {
        source.append("        // Arrange\n");

        if (plan.arrangeSteps() != null && !plan.arrangeSteps().isEmpty()) {
            for (String step : plan.arrangeSteps()) {
                source.append("        // ").append(safeComment(step)).append("\n");
            }
        }

        if (plan.requestData() != null && !plan.requestData().isEmpty()) {
            plan.requestData().forEach((key, value) -> {
                source.append("        // ").append(safeComment(key))
                        .append(" = ").append(safeComment(value)).append("\n");
            });
        }

        source.append("\n        // Act\n");

        JavaMethodSignature method = sourceAnalysis.targetMethod();

        if (method != null && !method.methodName().isBlank()) {
            String call = buildMethodCall(
                    sourceAnalysis.className(),
                    method,
                    testStyle,
                    assumptions,
                    unresolvedSymbols
            );
            source.append("        ").append(call).append("\n\n");
        } else {
            source.append("        // TODO: Call target method\n\n");
            unresolvedSymbols.add("Target method signature unknown");
        }

        source.append("        // Assert\n");

        if (hasHttp401Evidence(plan)) {
            source.append("        // Expected: HTTP 401 Unauthorized\n");
            source.append("        // TODO: Verify unauthorized response\n");
        } else if (plan.expectedFailureBeforeFix() != null
                && !plan.expectedFailureBeforeFix().isBlank()) {
            source.append("        // Expected failure: ")
                    .append(safeComment(plan.expectedFailureBeforeFix()))
                    .append("\n");
            source.append("        // TODO: Add specific assertions\n");
        } else {
            source.append("        // TODO: Add assertions based on expected failure\n");
            unresolvedSymbols.add("Assertion logic incomplete");
        }
    }

    private String buildMethodCall(
            String className,
            JavaMethodSignature method,
            String testStyle,
            List<String> assumptions,
            List<String> unresolvedSymbols
    ) {
        StringBuilder call = new StringBuilder();

        if (!method.returnType().equals("void")) {
            call.append(method.returnType()).append(" result = ");
        }

        String instance = toLowerCamelCase(className);

        if (testStyle.equals("MOCKITO_UNIT")) {
            call.append(instance);
        } else {
            call.append(instance);
            unresolvedSymbols.add("Instance initialization for " + className);
        }

        call.append(".").append(method.methodName()).append("(");

        List<String> args = new ArrayList<>();

        for (JavaParameterSignature param : method.parameters()) {
            String arg = generateDefaultValue(param.type(), assumptions);
            args.add(arg);
        }

        call.append(String.join(", ", args));
        call.append(");");

        return call.toString();
    }

    private String generateDefaultValue(String type, List<String> assumptions) {
        return switch (type) {
            case "String" -> "\"\"";
            case "boolean", "Boolean" -> "false";
            case "int", "Integer" -> "0";
            case "long", "Long" -> "0L";
            case "UUID" -> "UUID.randomUUID()";
            case "List" -> "List.of()";
            case "Map" -> "Map.of()";
            case "Optional" -> "Optional.empty()";
            default -> {
                assumptions.add("Using null for " + type + " parameter");
                yield "null";
            }
        };
    }

    private List<String> buildImports(
            TestPatternCandidate selected,
            JavaSourceSignatureAnalysis sourceAnalysis,
            String packageName,
            RegressionTestPlan plan,
            List<String> warnings
    ) {
        Set<String> imports = new LinkedHashSet<>();

        boolean isJUnit5 = selected.framework().contains("JUnit 5");
        boolean isJUnit4 = selected.framework().contains("JUnit 4");

        if (isJUnit5) {
            imports.add("org.junit.jupiter.api.Test");
            imports.add("org.junit.jupiter.api.DisplayName");
        } else if (isJUnit4) {
            imports.add("org.junit.Test");
        }

        if (selected.testStyle().equals("MOCKITO_UNIT")) {
            imports.add("org.junit.jupiter.api.extension.ExtendWith");
            imports.add("org.mockito.Mock");
            imports.add("org.mockito.InjectMocks");
            imports.add("org.mockito.junit.jupiter.MockitoExtension");
        } else if (selected.testStyle().equals("SPRING_BOOT_INTEGRATION")) {
            imports.add("org.springframework.boot.test.context.SpringBootTest");
        }

        for (String imp : selected.imports()) {
            if (imp.startsWith("org.junit") || imp.startsWith("org.mockito")
                    || imp.startsWith("org.springframework")) {
                if ((isJUnit5 && !imp.contains("org.junit.Test"))
                        || (isJUnit4 && !imp.contains("org.junit.jupiter"))) {
                    imports.add(imp);
                }
            }
        }

        String targetPackage = sourceAnalysis.packageName();
        if (!targetPackage.isBlank() && !targetPackage.equals(packageName)) {
            imports.add(targetPackage + "." + plan.targetProductionClass());
        }

        return new ArrayList<>(imports);
    }

    private List<String> extractDependencies(
            JavaSourceSignatureAnalysis sourceAnalysis,
            TestPatternCandidate selected
    ) {
        Set<String> dependencies = new LinkedHashSet<>();

        if (sourceAnalysis.constructor() != null) {
            sourceAnalysis.constructor().parameters()
                    .forEach(p -> dependencies.add(p.type()));
        }

        dependencies.addAll(sourceAnalysis.fieldTypes());
        dependencies.addAll(selected.mockedTypes());

        return new ArrayList<>(dependencies);
    }

    private double calculateConfidence(
            TestPatternCandidate selected,
            JavaSourceSignatureAnalysis sourceAnalysis,
            String packageName,
            RegressionTestPlan plan,
            List<String> unresolvedSymbols
    ) {
        double confidence = 0.0;

        if (selected != null && selected.score() > 0) {
            confidence += 0.20;
        }

        if (packageName.equals(extractPackageFromPath(plan.proposedFilePath()))) {
            confidence += 0.10;
        }

        if (!sourceAnalysis.className().isBlank()) {
            confidence += 0.15;
        }

        if (sourceAnalysis.targetMethod() != null
                && !sourceAnalysis.targetMethod().methodName().isBlank()) {
            confidence += 0.15;
        }

        if (sourceAnalysis.constructor() != null
                && !sourceAnalysis.constructor().parameters().isEmpty()) {
            confidence += 0.10;
        }

        if (!selected.framework().equals("UNKNOWN")) {
            confidence += 0.10;
        }

        if (hasHttp401Evidence(plan)) {
            confidence += 0.10;
        }

        if (unresolvedSymbols.isEmpty()) {
            confidence += 0.10;
        }

        return Math.min(confidence, 0.95);
    }

    private TestSourceReadiness determineReadiness(
            String source,
            TestPatternCandidate selected,
            List<String> unresolvedSymbols
    ) {
        if (source.isBlank() || selected == null || selected.score() == 0) {
            return TestSourceReadiness.INSUFFICIENT_CONTEXT;
        }

        if (!unresolvedSymbols.isEmpty()) {
            return TestSourceReadiness.NEEDS_MANUAL_COMPLETION;
        }

        return TestSourceReadiness.READY_FOR_REVIEW;
    }

    private PatternInformedTestSourceCandidate createInsufficientContextCandidate(
            UUID caseId,
            RegressionTestPlan plan,
            List<String> warnings,
            List<String> assumptions,
            List<String> unresolvedSymbols
    ) {
        return new PatternInformedTestSourceCandidate(
                caseId,
                plan.repositorySlug(),
                plan.sourceCommitSha(),
                "",
                plan.targetProductionClass(),
                plan.targetProductionMethod(),
                "",
                plan.proposedTestClass(),
                plan.proposedTestMethod(),
                normalizeRelativePath(plan.proposedFilePath()),
                plan.framework(),
                "PLAIN_JUNIT",
                "",
                "",
                TestSourceReadiness.INSUFFICIENT_CONTEXT,
                0.25,
                List.of(),
                List.of(),
                List.of(),
                unresolvedSymbols,
                assumptions,
                warnings,
                false,
                false,
                true
        );
    }

    private String resolvePackage(
            TestPatternCandidate selected,
            JavaSourceSignatureAnalysis sourceAnalysis,
            RegressionTestPlan plan
    ) {
        if (!selected.packageName().isBlank()) {
            return selected.packageName();
        }

        if (!sourceAnalysis.packageName().isBlank()) {
            return sourceAnalysis.packageName();
        }

        return extractPackageFromPath(plan.proposedFilePath());
    }

    private String extractPackageFromPath(String filePath) {
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

        return normalized.substring(startIndex, endIndex).replace('/', '.');
    }

    private boolean hasHttp401Evidence(RegressionTestPlan plan) {
        String failure = plan.expectedFailureBeforeFix();
        if (failure == null) {
            return false;
        }

        String lower = failure.toLowerCase();
        return lower.contains("401") || lower.contains("unauthorized");
    }

    private String normalizeRelativePath(String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.replace('\\', '/').trim();

        if (normalized.contains("../") || normalized.startsWith("/")
                || normalized.contains(":/")) {
            throw new IllegalArgumentException("Unsafe path: " + normalized);
        }

        return normalized;
    }

    private String toLowerCamelCase(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        return Character.toLowerCase(value.charAt(0))
                + value.substring(1);
    }

    private String safeComment(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\r", " ")
                .replace("\n", " ")
                .replace("*/", "* /")
                .trim();
    }

    private String escapeJava(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }

        return value.substring(0, maxLength);
    }

    private String calculateSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot calculate SHA-256.", exception);
        }
    }
}

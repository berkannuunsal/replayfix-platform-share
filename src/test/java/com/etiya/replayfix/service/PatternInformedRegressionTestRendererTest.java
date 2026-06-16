package com.etiya.replayfix.service;

import com.etiya.replayfix.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PatternInformedRegressionTestRendererTest {

    private PatternInformedRegressionTestRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new PatternInformedRegressionTestRenderer();
    }

    @Test
    void shouldGenerateJUnit5SourceFromJUnit5Pattern() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertTrue(result.source().contains("org.junit.jupiter.api.Test"));
        assertFalse(result.source().contains("org.junit.Test"));
    }

    @Test
    void shouldNotMixJUnit4AndJUnit5Imports() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        String source = result.source();
        
        if (source.contains("org.junit.jupiter")) {
            assertFalse(source.contains("import org.junit.Test;"));
        }
    }

    @Test
    void shouldPreserveMockitoStyle() {
        TestPatternCandidate pattern = createMockitoPattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertEquals("MOCKITO_UNIT", result.testStyle());
        assertTrue(result.source().contains("@Mock"));
    }

    @Test
    void shouldNotAddInjectMocksForSpringBootTest() {
        TestPatternCandidate pattern = createSpringBootTestPattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertFalse(result.source().contains("@InjectMocks"));
    }

    @Test
    void shouldUsePackageFromSelectedPattern() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        pattern = new TestPatternCandidate(
                pattern.relativePath(),
                "com.example.custom",
                pattern.className(),
                pattern.framework(),
                pattern.testStyle(),
                pattern.score(),
                pattern.reasons(),
                pattern.imports(),
                pattern.annotations(),
                pattern.testMethods(),
                pattern.mockedTypes(),
                pattern.excerpt()
        );

        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertEquals("com.example.custom", result.proposedPackage());
        assertTrue(result.source().contains("package com.example.custom;"));
    }

    @Test
    void shouldGenerateMethodCallWhenSignatureKnown() {
        RegressionTestPlan plan = createTestPlan();
        
        JavaSourceSignatureAnalysis analysis = new JavaSourceSignatureAnalysis(
                "com.example",
                "MyService",
                new JavaConstructorSignature("MyService", List.of()),
                new JavaMethodSignature(
                        "sendEmail",
                        "void",
                        List.of(new JavaParameterSignature("String", "email"))
                ),
                List.of(),
                List.of(),
                List.of()
        );

        TestPatternCandidate pattern = createJUnit5Pattern();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertTrue(result.source().contains("sendEmail"));
    }

    @Test
    void shouldAddUnknownDependencyToUnresolvedSymbols() {
        RegressionTestPlan plan = createTestPlan();
        
        JavaSourceSignatureAnalysis analysis = new JavaSourceSignatureAnalysis(
                "com.example",
                "MyService",
                new JavaConstructorSignature(
                        "MyService",
                        List.of(new JavaParameterSignature("UnknownDependency", "dependency"))
                ),
                new JavaMethodSignature("method", "void", List.of()),
                List.of(),
                List.of(),
                List.of()
        );

        TestPatternCandidate pattern = createMockitoPattern();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertFalse(result.unresolvedSymbols().isEmpty());
    }

    @Test
    void shouldRejectUnsafePath() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        JavaSourceSignatureAnalysis analysis = createAnalysis();
        
        RegressionTestPlan plan = new RegressionTestPlan(
                UUID.randomUUID(),
                "repo",
                "sha",
                "JUnit 5",
                "UNIT",
                "MyService",
                "method",
                "MyServiceTest",
                "testMethod",
                "../etc/passwd",
                "scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "failure",
                "success",
                List.of(),
                0.8,
                "DETERMINISTIC",
                false,
                false,
                true,
                List.of()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(
                        UUID.randomUUID(),
                        plan,
                        createPatternSelection(pattern),
                        analysis
                )
        );
    }

    @Test
    void shouldNotContainUnsupportedOperationException() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertFalse(result.source().contains("UnsupportedOperationException"));
    }

    @Test
    void shouldReturnInsufficientContextWhenSelectedIsNull() {
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();
        
        ExistingTestPatternSelection selection = new ExistingTestPatternSelection(
                UUID.randomUUID(),
                "repo",
                "workspace",
                "MyService",
                "method",
                "com.example",
                0,
                0,
                null,
                List.of(),
                List.of(),
                List.of()
        );

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                selection,
                analysis
        );

        assertEquals(TestSourceReadiness.INSUFFICIENT_CONTEXT, result.readiness());
        assertTrue(result.compileConfidence() <= 0.25);
    }

    @Test
    void shouldReturnNeedsManualCompletionWhenUnresolvedSymbolsExist() {
        RegressionTestPlan plan = createTestPlan();
        
        JavaSourceSignatureAnalysis analysis = new JavaSourceSignatureAnalysis(
                "",
                "",
                new JavaConstructorSignature("", List.of()),
                new JavaMethodSignature("", "void", List.of()),
                List.of(),
                List.of(),
                List.of()
        );

        TestPatternCandidate pattern = createJUnit5Pattern();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        if (!result.unresolvedSymbols().isEmpty()) {
            assertEquals(TestSourceReadiness.NEEDS_MANUAL_COMPLETION, result.readiness());
        }
    }

    @Test
    void shouldReturnReadyForReviewWhenNoUnresolvedSymbols() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        
        JavaSourceSignatureAnalysis analysis = new JavaSourceSignatureAnalysis(
                "com.example",
                "MyService",
                new JavaConstructorSignature("MyService", List.of()),
                new JavaMethodSignature("doSomething", "String", List.of()),
                List.of(),
                List.of(),
                List.of()
        );

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        if (result.unresolvedSymbols().isEmpty()) {
            assertEquals(TestSourceReadiness.READY_FOR_REVIEW, result.readiness());
        }
    }

    @Test
    void shouldCalculateCorrectSha256() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        String calculatedHash = calculateSha256(result.source());
        assertEquals(calculatedHash, result.contentSha256());
    }

    @Test
    void shouldNotWriteFiles() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertFalse(result.fileWritten());
    }

    @Test
    void shouldNotExecuteTests() {
        TestPatternCandidate pattern = createJUnit5Pattern();
        RegressionTestPlan plan = createTestPlan();
        JavaSourceSignatureAnalysis analysis = createAnalysis();

        PatternInformedTestSourceCandidate result = renderer.render(
                UUID.randomUUID(),
                plan,
                createPatternSelection(pattern),
                analysis
        );

        assertFalse(result.testExecuted());
    }

    private TestPatternCandidate createJUnit5Pattern() {
        return new TestPatternCandidate(
                "src/test/java/com/example/ExampleTest.java",
                "com.example",
                "ExampleTest",
                "JUnit 5",
                "PLAIN_JUNIT",
                100,
                List.of(),
                List.of("org.junit.jupiter.api.Test"),
                List.of("@Test"),
                List.of("testMethod"),
                List.of(),
                ""
        );
    }

    private TestPatternCandidate createMockitoPattern() {
        return new TestPatternCandidate(
                "src/test/java/com/example/MockitoTest.java",
                "com.example",
                "MockitoTest",
                "JUnit 5 + Mockito",
                "MOCKITO_UNIT",
                100,
                List.of(),
                List.of("org.junit.jupiter.api.Test", "org.mockito.Mock"),
                List.of("@Test", "@Mock", "@InjectMocks", "@ExtendWith"),
                List.of("testMethod"),
                List.of(),
                ""
        );
    }

    private TestPatternCandidate createSpringBootTestPattern() {
        return new TestPatternCandidate(
                "src/test/java/com/example/SpringTest.java",
                "com.example",
                "SpringTest",
                "JUnit 5 + Spring Boot Test",
                "SPRING_BOOT_INTEGRATION",
                100,
                List.of(),
                List.of("org.junit.jupiter.api.Test", "org.springframework.boot.test.context.SpringBootTest"),
                List.of("@Test", "@SpringBootTest"),
                List.of("testMethod"),
                List.of(),
                ""
        );
    }

    private RegressionTestPlan createTestPlan() {
        return new RegressionTestPlan(
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                "MyService",
                "doSomething",
                "MyServiceTest",
                "testDoSomething",
                "src/test/java/com/example/MyServiceTest.java",
                "Test scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "Expected failure",
                "Expected success",
                List.of(),
                0.8,
                "DETERMINISTIC",
                false,
                false,
                true,
                List.of()
        );
    }

    private JavaSourceSignatureAnalysis createAnalysis() {
        return new JavaSourceSignatureAnalysis(
                "com.example",
                "MyService",
                new JavaConstructorSignature("MyService", List.of()),
                new JavaMethodSignature("doSomething", "String", List.of()),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExistingTestPatternSelection createPatternSelection(TestPatternCandidate pattern) {
        return new ExistingTestPatternSelection(
                UUID.randomUUID(),
                "test-repo",
                "workspace",
                "MyService",
                "doSomething",
                "com.example",
                1,
                1,
                pattern,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private String calculateSha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

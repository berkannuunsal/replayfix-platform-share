package com.etiya.replayfix.service;

import com.etiya.replayfix.model.RegressionTestPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegressionTestSourceRendererTest {

    private RegressionTestSourceRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new RegressionTestSourceRenderer();
    }

    @Test
    void shouldGenerateJavaSource() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/TestClass.java",
                "TestClass",
                "shouldReproduceIssue",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertNotNull(source);
        assertFalse(source.isBlank());
        assertTrue(source.contains("package com.example;"));
        assertTrue(source.contains("class TestClass"));
    }

    @Test
    void shouldIncludeProposedClassName() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/MyServiceTest.java",
                "MyServiceTest",
                "testMethod",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("class MyServiceTest"));
    }

    @Test
    void shouldIncludeProposedMethodName() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/Test.java",
                "Test",
                "shouldReproduceAuthFailure",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("void shouldReproduceAuthFailure()"));
    }

    @Test
    void shouldIncludeTestAnnotation() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/Test.java",
                "Test",
                "testMethod",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("@Test"));
        assertTrue(source.contains("import org.junit.jupiter.api.Test;"));
    }

    @Test
    void shouldIncludeHumanApprovalComment() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/Test.java",
                "Test",
                "testMethod",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("Human approval was required"));
    }

    @Test
    void shouldIncludeUnsupportedOperationException() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/Test.java",
                "Test",
                "testMethod",
                "JUnit 5"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("UnsupportedOperationException"));
        assertTrue(source.contains("ReplayFix regression scaffold"));
    }

    @Test
    void shouldThrowExceptionWhenPathOutsideSrcTestJava() {
        RegressionTestPlan plan = createTestPlan(
                "src/main/java/com/example/Test.java",
                "Test",
                "testMethod",
                "JUnit 5"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(plan)
        );
    }

    @Test
    void shouldThrowExceptionWhenPathContainsTraversal() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/../../etc/passwd",
                "Test",
                "testMethod",
                "JUnit 5"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(plan)
        );
    }

    @Test
    void shouldIncludeMockitoAnnotationsWhenFrameworkUsesMockito() {
        RegressionTestPlan plan = createTestPlan(
                "src/test/java/com/example/Test.java",
                "Test",
                "testMethod",
                "JUnit 5 + Mockito"
        );

        String source = renderer.render(plan);

        assertTrue(source.contains("@ExtendWith(MockitoExtension.class)"));
        assertTrue(source.contains("import org.mockito.junit.jupiter.MockitoExtension;"));
    }

    @Test
    void shouldSanitizeJiraDescriptionFromInjection() {
        RegressionTestPlan plan = new RegressionTestPlan(
                UUID.randomUUID(),
                "repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                "Target",
                "method",
                "TestClass",
                "testMethod",
                "src/test/java/Test.java",
                "Injection attempt */ class Evil { /* scenario",
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
                "DETERMINISTIC_PLAN_ONLY",
                false,
                false,
                true,
                List.of()
        );

        String source = renderer.render(plan);

        assertFalse(source.contains("class Evil"));
        assertTrue(source.contains("* /"));
    }

    private RegressionTestPlan createTestPlan(
            String proposedFilePath,
            String proposedTestClass,
            String proposedTestMethod,
            String framework
    ) {
        return new RegressionTestPlan(
                UUID.randomUUID(),
                "test-repo",
                "commit-sha",
                framework,
                "UNIT_REGRESSION",
                "ProductionClass",
                "productionMethod",
                proposedTestClass,
                proposedTestMethod,
                proposedFilePath,
                "Test scenario",
                List.of("Precondition 1"),
                List.of("Arrange step 1"),
                List.of("Act step 1"),
                List.of("Assert step 1"),
                List.of("Mock 1"),
                List.of(),
                Map.of("key", "value"),
                Map.of("expected", "result"),
                "Expected failure",
                "Expected success",
                List.of("JIRA_ISSUE"),
                0.75,
                "DETERMINISTIC_PLAN_ONLY",
                false,
                false,
                true,
                List.of()
        );
    }
}

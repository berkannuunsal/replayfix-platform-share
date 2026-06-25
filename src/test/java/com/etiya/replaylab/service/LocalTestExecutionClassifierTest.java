package com.etiya.replaylab.service;

import com.etiya.replaylab.model.LocalTestExecutionStatus;
import com.etiya.replaylab.model.RegressionTestPlan;
import com.etiya.replaylab.model.SafeProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalTestExecutionClassifierTest {

    private LocalTestExecutionClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new LocalTestExecutionClassifier();
    }

    @Test
    void shouldClassifyTimeout() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                60000,
                null,
                true,
                "Test output"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.TIMEOUT, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
    }

    @Test
    void shouldClassifyCompilationFailure() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                5000,
                1,
                false,
                "[ERROR] COMPILATION FAILURE\n[ERROR] cannot find symbol"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.COMPILE_FAILURE, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
        assertTrue(result.matchedSignals().stream()
                .anyMatch(s -> s.contains("compiler failure")));
    }

    @Test
    void shouldClassifyNoTestsFound() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                3000,
                0,
                false,
                "[INFO] No tests matching pattern"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.TEST_NOT_FOUND, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
    }

    @Test
    void shouldClassifyTestPassedUnexpected() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                8000,
                0,
                false,
                "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.TEST_PASSED_UNEXPECTED_BEFORE_FIX, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
    }

    @Test
    void shouldClassifyScaffoldFailure() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                4000,
                1,
                false,
                "java.lang.UnsupportedOperationException: ReplayLab regression scaffold"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.SCAFFOLD_FAILURE_NOT_REPRODUCED, result.status());
        assertFalse(result.defectReproduced());
        assertTrue(result.scaffoldFailure());
    }

    @Test
    void shouldClassifyDefectReproducedWith401Signal() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                6000,
                1,
                false,
                "Expected: <200> but was: <401>\n" +
                        "HTTP Status 401 Unauthorized\n" +
                        "AssertionError at line 45"
        );

        RegressionTestPlan plan = createTestPlan("Expected HTTP 401 error");

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.DEFECT_REPRODUCED, result.status());
        assertTrue(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
        assertTrue(result.matchedSignals().stream()
                .anyMatch(s -> s.contains("401") || s.contains("unauthorized")));
    }

    @Test
    void shouldClassifyInfrastructureFailure() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                2000,
                1,
                false,
                "Could not transfer artifact\n" +
                        "Connection refused: connect\n" +
                        "Unknown host: maven.repo.com"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.INFRASTRUCTURE_FAILURE, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
    }

    @Test
    void shouldClassifyUnclassifiedFailure() {
        SafeProcessResult process = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                5000,
                1,
                false,
                "Some random test failure without known patterns"
        );

        RegressionTestPlan plan = createTestPlan();

        var result = classifier.classify(process, plan);

        assertEquals(LocalTestExecutionStatus.TEST_FAILURE_UNCLASSIFIED, result.status());
        assertFalse(result.defectReproduced());
        assertFalse(result.scaffoldFailure());
    }

    private RegressionTestPlan createTestPlan() {
        return createTestPlan("Generic failure");
    }

    private RegressionTestPlan createTestPlan(String expectedFailure) {
        return new RegressionTestPlan(
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                "TargetClass",
                "targetMethod",
                "TestClass",
                "testMethod",
                "src/test/java/TestClass.java",
                "Test scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                expectedFailure,
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
}

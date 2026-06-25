package com.etiya.replaylab.service;

import com.etiya.replaylab.model.DeterministicRootCauseReport;
import com.etiya.replaylab.model.RegressionTestPlan;
import com.etiya.replaylab.model.RootCauseMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegressionTestPlanBuilderTest {

    private RegressionTestPlanBuilder builder;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        builder = new RegressionTestPlanBuilder();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldBuildPlanWithRequiredDefaults() throws Exception {
        UUID caseId = UUID.randomUUID();
        String repositorySlug = "ntf-engine";
        String sourceCommitSha = "6c4690dc1a52bc88f1a3197247cee43be3acbaff";

        DeterministicRootCauseReport rootCause =
                new DeterministicRootCauseReport(
                        "TEST-123",
                        "HYPOTHESIS",
                        "AUTHENTICATION_FAILURE",
                        "Authentication or token propagation failure detected",
                        0.82,
                        List.of("ntf-engine"),
                        List.of("Timeline evidence"),
                        List.of(),
                        List.of("Verify authentication"),
                        new RootCauseMetrics(15, 8, 5, 3, 10, 6, 4, 12, 5, 4, 0)
                );

        String jiraJson = """
                {
                    "summary": "401 Unauthorized error in notification service",
                    "description": "Users receive 401 when accessing /api/notifications endpoint"
                }
                """;

        JsonNode jiraNode = objectMapper.readTree(jiraJson);

        RegressionTestPlan plan = builder.build(
                caseId,
                repositorySlug,
                sourceCommitSha,
                rootCause,
                jiraNode,
                null,
                null
        );

        assertNotNull(plan);
        assertEquals("DETERMINISTIC_PLAN_ONLY", plan.generationMode());
        assertFalse(plan.writeAuthorized());
        assertFalse(plan.executionAuthorized());
        assertTrue(plan.humanApprovalRequired());
        assertTrue(plan.proposedFilePath().startsWith("src/test/"));
        assertFalse(plan.proposedFilePath().contains("../"));
        assertFalse(plan.assertions().isEmpty());
        assertTrue(plan.confidence() >= 0.0 && plan.confidence() <= 1.0);
        assertTrue(plan.expectedFailureBeforeFix().contains("401")
                || plan.expectedFailureBeforeFix().contains("unauthorized"));
    }

    @Test
    void shouldDetectIntegrationTestTypeFor401Issue() throws Exception {
        UUID caseId = UUID.randomUUID();

        DeterministicRootCauseReport rootCause =
                new DeterministicRootCauseReport(
                        "TEST-123",
                        "HYPOTHESIS",
                        "AUTHENTICATION_FAILURE",
                        "HTTP 401 unauthorized detected",
                        0.75,
                        List.of("backend"),
                        List.of(),
                        List.of(),
                        List.of(),
                        new RootCauseMetrics(10, 5, 3, 2, 8, 4, 3, 10, 3, 2, 0)
                );

        String jiraJson = """
                {
                    "summary": "401 error",
                    "description": "Endpoint returns unauthorized"
                }
                """;

        JsonNode jiraNode = objectMapper.readTree(jiraJson);

        RegressionTestPlan plan = builder.build(
                caseId,
                "backend",
                "abc123",
                rootCause,
                jiraNode,
                null,
                null
        );

        assertEquals("INTEGRATION_REGRESSION", plan.testType());
        assertTrue(plan.assertions().stream()
                .anyMatch(a -> a.toLowerCase().contains("unauthorized")
                        || a.toLowerCase().contains("401")));
    }

    @Test
    void shouldInferClassNameWhenNotFoundInSource() throws Exception {
        UUID caseId = UUID.randomUUID();

        DeterministicRootCauseReport rootCause =
                new DeterministicRootCauseReport(
                        "TEST-123",
                        "HYPOTHESIS",
                        "UNCLASSIFIED",
                        "Notification service issue",
                        0.60,
                        List.of("ntf"),
                        List.of(),
                        List.of(),
                        List.of(),
                        new RootCauseMetrics(5, 3, 2, 1, 5, 3, 2, 8, 2, 1, 0)
                );

        String jiraJson = """
                {
                    "summary": "Notification failure",
                    "description": "Notification not sent"
                }
                """;

        JsonNode jiraNode = objectMapper.readTree(jiraJson);

        RegressionTestPlan plan = builder.build(
                caseId,
                "ntf-engine",
                "def456",
                rootCause,
                jiraNode,
                null,
                null
        );

        assertTrue(plan.targetProductionClass().contains("Notification")
                || plan.targetProductionClass().contains("Service"));
        assertTrue(plan.warnings().stream()
                .anyMatch(w -> w.contains("inferred")));
    }

    @Test
    void shouldValidateProposedFilePathUnderSrcTest() throws Exception {
        UUID caseId = UUID.randomUUID();

        DeterministicRootCauseReport rootCause =
                new DeterministicRootCauseReport(
                        "TEST-123",
                        "HYPOTHESIS",
                        "TIMEOUT",
                        "Timeout detected",
                        0.65,
                        List.of("backend"),
                        List.of(),
                        List.of(),
                        List.of(),
                        new RootCauseMetrics(8, 4, 3, 2, 6, 3, 2, 10, 3, 2, 0)
                );

        String jiraJson = """
                {
                    "summary": "Timeout",
                    "description": "Operation timed out"
                }
                """;

        JsonNode jiraNode = objectMapper.readTree(jiraJson);

        RegressionTestPlan plan = builder.build(
                caseId,
                "backend",
                "ghi789",
                rootCause,
                jiraNode,
                null,
                null
        );

        String normalizedPath = plan.proposedFilePath().replace('\\', '/');
        assertTrue(normalizedPath.startsWith("src/test/java/"));
        assertFalse(normalizedPath.contains(".."));
        assertFalse(normalizedPath.startsWith("/"));
    }

    @Test
    void shouldCalculateConfidenceBasedOnAvailableEvidence() throws Exception {
        UUID caseId = UUID.randomUUID();

        DeterministicRootCauseReport rootCause =
                new DeterministicRootCauseReport(
                        "TEST-123",
                        "HYPOTHESIS",
                        "NULL_POINTER",
                        "Null pointer exception",
                        0.70,
                        List.of("loyalty"),
                        List.of("Evidence 1"),
                        List.of(),
                        List.of("Fix NPE"),
                        new RootCauseMetrics(12, 6, 4, 4, 10, 5, 3, 15, 4, 3, 0)
                );

        String jiraJson = """
                {
                    "summary": "NPE in loyalty service",
                    "description": "Null pointer exception"
                }
                """;

        String sourceJson = """
                {
                    "repository": "loyalty",
                    "files": ["LoyaltyService.java"]
                }
                """;

        JsonNode jiraNode = objectMapper.readTree(jiraJson);
        JsonNode sourceNode = objectMapper.readTree(sourceJson);

        RegressionTestPlan planWithSource = builder.build(
                caseId,
                "loyalty",
                "jkl012",
                rootCause,
                jiraNode,
                sourceNode,
                null
        );

        RegressionTestPlan planWithoutSource = builder.build(
                caseId,
                "loyalty",
                "jkl012",
                rootCause,
                jiraNode,
                null,
                null
        );

        assertTrue(planWithSource.confidence() > planWithoutSource.confidence());
    }
}

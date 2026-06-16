package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RegressionTestPlan(
        UUID caseId,
        String repositorySlug,
        String sourceCommitSha,
        String framework,
        String testType,
        String targetProductionClass,
        String targetProductionMethod,
        String proposedTestClass,
        String proposedTestMethod,
        String proposedFilePath,
        String scenario,
        List<String> preconditions,
        List<String> arrangeSteps,
        List<String> actSteps,
        List<String> assertions,
        List<String> mocks,
        List<String> fixtures,
        Map<String, String> requestData,
        Map<String, String> expectedResult,
        String expectedFailureBeforeFix,
        String expectedResultAfterFix,
        List<String> sourceEvidence,
        double confidence,
        String generationMode,
        boolean writeAuthorized,
        boolean executionAuthorized,
        boolean humanApprovalRequired,
        List<String> warnings
) {
}

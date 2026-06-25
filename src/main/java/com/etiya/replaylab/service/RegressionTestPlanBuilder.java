package com.etiya.replaylab.service;

import com.etiya.replaylab.model.DeterministicRootCauseReport;
import com.etiya.replaylab.model.RegressionTestPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RegressionTestPlanBuilder {

    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile(
                    "(?m)\\bclass\\s+([A-Z][A-Za-z0-9_]*)"
            );

    private static final Pattern JAVA_METHOD_PATTERN =
            Pattern.compile(
                    "(?m)(?:public|protected|private)\\s+"
                            + "(?:static\\s+)?"
                            + "[A-Za-z0-9_<>,.?\\[\\]]+\\s+"
                            + "([a-z][A-Za-z0-9_]*)\\s*\\("
            );

    public RegressionTestPlan build(
            UUID caseId,
            String repositorySlug,
            String sourceCommitSha,
            DeterministicRootCauseReport rootCauseReport,
            JsonNode jiraNode,
            JsonNode sourceContextNode,
            JsonNode jenkinsNode
    ) {
        List<String> warnings =
                new ArrayList<>();

        String jiraSummary =
                firstText(
                        jiraNode,
                        "summary",
                        "fields.summary"
                );

        String jiraDescription =
                firstText(
                        jiraNode,
                        "description",
                        "plainDescription",
                        "fields.description"
                );

        String probableRootCause =
                readProbableRootCause(
                        rootCauseReport
                );

        String sourceText =
                sourceContextNode == null
                        ? ""
                        : sourceContextNode.toString();

        String targetClass =
                firstMatch(
                        JAVA_CLASS_PATTERN,
                        sourceText
                );

        String targetMethod =
                firstMatch(
                        JAVA_METHOD_PATTERN,
                        sourceText
                );

        if (targetClass.isBlank()) {
            targetClass =
                    inferClassName(
                            jiraSummary,
                            probableRootCause
                    );

            warnings.add(
                    "Target production class was inferred "
                            + "because source context did not expose "
                            + "a Java class declaration."
            );
        }

        if (targetMethod.isBlank()) {
            targetMethod =
                    inferMethodName(
                            jiraSummary,
                            jiraDescription,
                            probableRootCause
                    );

            warnings.add(
                    "Target production method was inferred."
            );
        }

        String proposedTestClass =
                sanitizeJavaIdentifier(
                        targetClass
                ) + "ReplayLabRegressionTest";

        String proposedTestMethod =
                "shouldReproduce"
                        + toPascalCase(
                                extractScenarioToken(
                                        jiraSummary,
                                        probableRootCause
                                )
                        );

        String packagePath =
                inferPackagePath(
                        sourceContextNode
                );

        String proposedFilePath =
                "src/test/java/"
                        + packagePath
                        + proposedTestClass
                        + ".java";

        List<String> preconditions =
                buildPreconditions(
                        jiraDescription,
                        probableRootCause
                );

        List<String> arrange =
                buildArrangeSteps(
                        jiraDescription,
                        probableRootCause
                );

        List<String> act =
                buildActSteps(
                        jiraDescription,
                        probableRootCause
                );

        List<String> assertions =
                buildAssertions(
                        jiraDescription,
                        probableRootCause
                );

        List<String> mocks =
                buildMocks(
                        jiraDescription,
                        probableRootCause
                );

        Map<String, String> requestData =
                extractRequestData(
                        jiraDescription
                );

        Map<String, String> expectedResult =
                extractExpectedResult(
                        jiraDescription
                );

        double confidence =
                calculateConfidence(
                        targetClass,
                        targetMethod,
                        assertions,
                        sourceContextNode,
                        jenkinsNode
                );

        List<String> sourceEvidence =
                List.of(
                        "JIRA_ISSUE",
                        "AI_ROOT_CAUSE:"
                                + "deterministic-root-cause-jenkins-validated",
                        "SOURCE_CONTEXT:"
                                + "jenkins-validated-source-context",
                        "JENKINS_BUILD_CONTEXT:"
                                + "jenkins-incident-version-validator"
                );

        return new RegressionTestPlan(
                caseId,
                repositorySlug,
                sourceCommitSha,
                detectFramework(sourceText),
                detectTestType(
                        probableRootCause,
                        jiraDescription
                ),
                targetClass,
                targetMethod,
                proposedTestClass,
                proposedTestMethod,
                proposedFilePath,
                buildScenario(
                        jiraSummary,
                        probableRootCause
                ),
                preconditions,
                arrange,
                act,
                assertions,
                mocks,
                List.of(),
                requestData,
                expectedResult,
                buildExpectedFailure(
                        probableRootCause,
                        jiraDescription
                ),
                buildExpectedAfterFix(
                        jiraDescription
                ),
                sourceEvidence,
                confidence,
                "DETERMINISTIC_PLAN_ONLY",
                false,
                false,
                true,
                warnings
        );
    }

    private String readProbableRootCause(
            DeterministicRootCauseReport report
    ) {
        if (report == null) {
            return "";
        }

        return report.probableCause();
    }

    private String detectFramework(
            String sourceText
    ) {
        String normalized =
                sourceText.toLowerCase(
                        Locale.ROOT
                );

        if (normalized.contains(
                "springboottest"
        )) {
            return "JUnit 5 + Spring Boot Test";
        }

        if (normalized.contains(
                "mockito"
        )) {
            return "JUnit 5 + Mockito";
        }

        return "JUnit 5";
    }

    private String detectTestType(
            String rootCause,
            String description
    ) {
        String text =
                normalize(
                        rootCause
                                + " "
                                + description
                );

        if (text.contains("http")
                || text.contains("endpoint")
                || text.contains("unauthorized")
                || text.contains("401")) {
            return "INTEGRATION_REGRESSION";
        }

        return "UNIT_REGRESSION";
    }

    private List<String> buildPreconditions(
            String description,
            String rootCause
    ) {
        List<String> values =
                new ArrayList<>();

        values.add(
                "Use the Jenkins-validated source commit."
        );

        if (containsAny(
                description,
                "payment",
                "moneris"
        )) {
            values.add(
                    "Prepare a payment flow where the initial payment "
                            + "attempt fails and retry succeeds."
            );
        }

        if (containsAny(
                description,
                "bar",
                "recurring_payment_callback"
        )) {
            values.add(
                    "Prepare BAR and RECURRING_PAYMENT_CALLBACK "
                            + "order records in the expected initial state."
            );
        }

        if (containsAny(
                rootCause,
                "authentication",
                "token",
                "401",
                "unauthorized"
        )) {
            values.add(
                    "Prepare authentication/token collaborators "
                            + "so the failing authorization path can be reproduced."
            );
        }

        return values;
    }

    private List<String> buildArrangeSteps(
            String description,
            String rootCause
    ) {
        List<String> values =
                new ArrayList<>();

        values.add(
                "Create the minimum domain/request objects "
                        + "needed by the target method."
        );

        if (containsAny(
                description,
                "status 12",
                "status = 12"
        )) {
            values.add(
                    "Initialize the affected order with status 12."
            );
        }

        if (containsAny(
                rootCause,
                "token",
                "authorization",
                "401"
        )) {
            values.add(
                    "Mock or stub the downstream authentication response "
                            + "that triggers HTTP 401."
            );
        }

        return values;
    }

    private List<String> buildActSteps(
            String description,
            String rootCause
    ) {
        List<String> values =
                new ArrayList<>();

        if (containsAny(
                description,
                "/customerorder/omintegration/complete",
                "omintegration/complete"
        )) {
            values.add(
                    "Invoke the customer-order completion flow "
                            + "for /customerorder/omintegration/complete."
            );
        } else {
            values.add(
                    "Invoke the selected production method "
                            + "with the arranged incident inputs."
            );
        }

        return values;
    }

    private List<String> buildAssertions(
            String description,
            String rootCause
    ) {
        List<String> values =
                new ArrayList<>();

        if (containsAny(
                description,
                "401",
                "unauthorized"
        )) {
            values.add(
                    "Before the fix, verify that the call follows "
                            + "the unauthorized failure path."
            );
        }

        if (containsAny(
                description,
                "camunda",
                "instance is deleted"
        )) {
            values.add(
                    "Verify that the process instance is not "
                            + "prematurely removed after the failing call."
            );
        }

        if (containsAny(
                description,
                "recurring_payment_callback",
                "status 12"
        )) {
            values.add(
                    "Verify that the callback/order does not remain "
                            + "stuck in status 12 after the fix."
            );
        }

        if (values.isEmpty()) {
            values.add(
                    "Assert the incident behavior before the fix "
                            + "and the expected business behavior after the fix."
            );
        }

        return values;
    }

    private List<String> buildMocks(
            String description,
            String rootCause
    ) {
        List<String> values =
                new ArrayList<>();

        String text =
                normalize(
                        description
                                + " "
                                + rootCause
                );

        if (text.contains("moneris")
                || text.contains("payment")) {
            values.add(
                    "Payment provider client"
            );
        }

        if (text.contains("camunda")) {
            values.add(
                    "Camunda/runtime process service"
            );
        }

        if (text.contains("token")
                || text.contains("401")
                || text.contains("unauthorized")) {
            values.add(
                    "Authentication/token provider"
            );
        }

        return values;
    }

    private Map<String, String> extractRequestData(
            String description
    ) {
        Map<String, String> values =
                new LinkedHashMap<>();

        if (containsAny(
                description,
                "/customerorder/omintegration/complete"
        )) {
            values.put(
                    "endpoint",
                    "/customerorder/omintegration/complete"
            );
        }

        if (containsAny(
                description,
                "bar"
        )) {
            values.put(
                    "orderType",
                    "BAR"
            );
        }

        if (containsAny(
                description,
                "recurring_payment_callback"
        )) {
            values.put(
                    "callbackOrderType",
                    "RECURRING_PAYMENT_CALLBACK"
            );
        }

        return values;
    }

    private Map<String, String> extractExpectedResult(
            String description
    ) {
        Map<String, String> values =
                new LinkedHashMap<>();

        if (containsAny(
                description,
                "status 12"
        )) {
            values.put(
                    "finalOrderStatus",
                    "Not 12; expected configured success status"
            );
        }

        if (containsAny(
                description,
                "401"
        )) {
            values.put(
                    "httpStatus",
                    "Successful/non-401 response after fix"
            );
        }

        return values;
    }

    private String buildExpectedFailure(
            String rootCause,
            String description
    ) {
        if (containsAny(
                rootCause + " " + description,
                "401",
                "unauthorized"
        )) {
            return "The generated regression test must fail "
                    + "against the incident version because the flow "
                    + "receives or propagates HTTP 401 incorrectly.";
        }

        return "The generated regression test must fail "
                + "against the incident version by reproducing "
                + "the Jira-described defect.";
    }

    private String buildExpectedAfterFix(
            String description
    ) {
        if (containsAny(
                description,
                "status 12"
        )) {
            return "The order flow completes and no affected order "
                    + "remains stuck in status 12.";
        }

        return "The regression test passes while preserving "
                + "the expected business behavior.";
    }

    private String buildScenario(
            String jiraSummary,
            String rootCause
    ) {
        String value =
                !jiraSummary.isBlank()
                        ? jiraSummary
                        : rootCause;

        return truncate(
                value,
                500
        );
    }

    private String inferClassName(
            String jiraSummary,
            String rootCause
    ) {
        String text =
                normalize(
                        jiraSummary
                                + " "
                                + rootCause
                );

        if (text.contains("notification")
                || text.contains("ntf")) {
            return "NotificationService";
        }

        if (text.contains("loyalty")) {
            return "LoyaltyService";
        }

        if (text.contains("customerorder")
                || text.contains("customer order")) {
            return "CustomerOrderService";
        }

        return "IncidentTargetService";
    }

    private String inferMethodName(
            String jiraSummary,
            String description,
            String rootCause
    ) {
        String text =
                normalize(
                        jiraSummary
                                + " "
                                + description
                                + " "
                                + rootCause
                );

        if (text.contains("omintegration/complete")) {
            return "completeOrderIntegration";
        }

        if (text.contains("notification")) {
            return "sendNotification";
        }

        return "executeIncidentFlow";
    }

    private String inferPackagePath(
            JsonNode sourceContextNode
    ) {
        if (sourceContextNode == null) {
            return "com/etiya/replaylab/generated/";
        }

        String packageName =
                sourceContextNode.path(
                        "packageName"
                ).asText("");

        if (packageName.isBlank()) {
            packageName =
                    sourceContextNode.path(
                            "targetPackage"
                    ).asText("");
        }

        if (packageName.isBlank()) {
            return "com/etiya/replaylab/generated/";
        }

        return packageName.replace(
                '.',
                '/'
        ) + "/";
    }

    private double calculateConfidence(
            String targetClass,
            String targetMethod,
            List<String> assertions,
            JsonNode sourceContext,
            JsonNode jenkins
    ) {
        double confidence = 0.35;

        if (!targetClass.isBlank()) {
            confidence += 0.15;
        }

        if (!targetMethod.isBlank()) {
            confidence += 0.15;
        }

        if (assertions != null
                && !assertions.isEmpty()) {
            confidence += 0.15;
        }

        if (sourceContext != null
                && !sourceContext.isEmpty()) {
            confidence += 0.10;
        }

        if (jenkins != null
                && !jenkins.isEmpty()) {
            confidence += 0.10;
        }

        return Math.min(
                confidence,
                0.95
        );
    }

    private String firstText(
            JsonNode node,
            String... paths
    ) {
        if (node == null) {
            return "";
        }

        for (String path : paths) {
            JsonNode current = node;

            for (String part :
                    path.split("\\.")) {
                current =
                        current.path(part);
            }

            String value =
                    current.asText("");

            if (!value.isBlank()) {
                return value;
            }
        }

        return "";
    }

    private String firstMatch(
            Pattern pattern,
            String text
    ) {
        if (text == null
                || text.isBlank()) {
            return "";
        }

        Matcher matcher =
                pattern.matcher(text);

        return matcher.find()
                ? matcher.group(1)
                : "";
    }

    private String extractScenarioToken(
            String summary,
            String rootCause
    ) {
        String text =
                !summary.isBlank()
                        ? summary
                        : rootCause;

        String normalized =
                text.replaceAll(
                        "[^A-Za-z0-9 ]",
                        " "
                ).trim();

        if (normalized.isBlank()) {
            return "Incident";
        }

        String[] parts =
                normalized.split("\\s+");

        StringBuilder result =
                new StringBuilder();

        for (int i = 0;
                i < Math.min(parts.length, 6);
                i++) {
            result.append(
                    toPascalCase(parts[i])
            );
        }

        return result.toString();
    }

    private String toPascalCase(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "Incident";
        }

        String normalized =
                value.replaceAll(
                        "[^A-Za-z0-9]+",
                        " "
                ).trim();

        StringBuilder result =
                new StringBuilder();

        for (String part :
                normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }

            result.append(
                    Character.toUpperCase(
                            part.charAt(0)
                    )
            );

            if (part.length() > 1) {
                result.append(
                        part.substring(1)
                );
            }
        }

        return result.isEmpty()
                ? "Incident"
                : result.toString();
    }

    private String sanitizeJavaIdentifier(
            String value
    ) {
        String result =
                value == null
                        ? ""
                        : value.replaceAll(
                                "[^A-Za-z0-9_$]",
                                ""
                        );

        if (result.isBlank()) {
            return "IncidentTarget";
        }

        if (!Character.isJavaIdentifierStart(
                result.charAt(0)
        )) {
            return "Incident" + result;
        }

        return result;
    }

    private boolean containsAny(
            String value,
            String... expected
    ) {
        String normalized =
                normalize(value);

        for (String item : expected) {
            if (normalized.contains(
                    normalize(item)
            )) {
                return true;
            }
        }

        return false;
    }

    private String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value.toLowerCase(
                        Locale.ROOT
                );
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        return value.length()
                <= maxLength
                ? value
                : value.substring(
                        0,
                        maxLength
                );
    }
}

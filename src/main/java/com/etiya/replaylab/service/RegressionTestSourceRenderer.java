package com.etiya.replaylab.service;

import com.etiya.replaylab.model.RegressionTestPlan;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RegressionTestSourceRenderer {

    public String render(
            RegressionTestPlan plan
    ) {
        validatePlan(plan);

        String packageName =
                packageNameFromPath(
                        plan.proposedFilePath()
                );

        String className =
                sanitizeJavaIdentifier(
                        plan.proposedTestClass(),
                        "ReplayLabRegressionTest"
                );

        String methodName =
                sanitizeJavaIdentifier(
                        plan.proposedTestMethod(),
                        "shouldReproduceIncident"
                );

        StringBuilder source =
                new StringBuilder();

        if (!packageName.isBlank()) {
            source.append("package ")
                    .append(packageName)
                    .append(";\n\n");
        }

        source.append(
                "import org.junit.jupiter.api.DisplayName;\n"
        );

        source.append(
                "import org.junit.jupiter.api.Test;\n"
        );

        if (usesMockito(plan)) {
            source.append(
                    "import org.junit.jupiter.api.extension.ExtendWith;\n"
            );

            source.append(
                    "import org.mockito.junit.jupiter.MockitoExtension;\n"
            );
        }

        source.append("\n");

        if (usesMockito(plan)) {
            source.append(
                    "@ExtendWith(MockitoExtension.class)\n"
            );
        }

        source.append("class ")
                .append(className)
                .append(" {\n\n");

        source.append("    @Test\n");

        source.append("    @DisplayName(\"")
                .append(
                        escapeJava(
                                truncate(
                                        plan.scenario(),
                                        180
                                )
                        )
                )
                .append("\")\n");

        source.append("    void ")
                .append(methodName)
                .append("() {\n");

        source.append(
                "        // ReplayLab generated regression test scaffold.\n"
        );

        source.append(
                "        // Human approval was required before this file was written.\n"
        );

        source.append(
                "        // TODO Replace placeholders with project-specific collaborators.\n\n"
        );

        appendSection(
                source,
                "Preconditions",
                plan.preconditions()
        );

        appendSection(
                source,
                "Arrange",
                plan.arrangeSteps()
        );

        appendMapSection(
                source,
                "Request data",
                plan.requestData()
        );

        appendSection(
                source,
                "Act",
                plan.actSteps()
        );

        appendSection(
                source,
                "Assertions",
                plan.assertions()
        );

        appendMapSection(
                source,
                "Expected result",
                plan.expectedResult()
        );

        source.append("\n");

        source.append(
                "        throw new UnsupportedOperationException(\n"
        );

        source.append(
                "                \"ReplayLab regression scaffold: implement project-specific arrange/act/assert before execution.\"\n"
        );

        source.append("        );\n");

        source.append("    }\n");

        source.append("}\n");

        return source.toString();
    }

    private void appendSection(
            StringBuilder source,
            String title,
            List<String> values
    ) {
        source.append("        // ")
                .append(title)
                .append(":\n");

        if (values == null
                || values.isEmpty()) {
            source.append(
                    "        // - No deterministic item was generated.\n"
            );

            return;
        }

        for (String value : values) {
            source.append("        // - ")
                    .append(
                            safeComment(value)
                    )
                    .append("\n");
        }

        source.append("\n");
    }

    private void appendMapSection(
            StringBuilder source,
            String title,
            Map<String, String> values
    ) {
        source.append("        // ")
                .append(title)
                .append(":\n");

        if (values == null
                || values.isEmpty()) {
            source.append(
                    "        // - No deterministic value was generated.\n\n"
            );

            return;
        }

        values.forEach(
                (key, value) ->
                        source.append("        // - ")
                                .append(
                                        safeComment(key)
                                )
                                .append(" = ")
                                .append(
                                        safeComment(value)
                                )
                                .append("\n")
        );

        source.append("\n");
    }

    private boolean usesMockito(
            RegressionTestPlan plan
    ) {
        String framework =
                plan.framework() == null
                        ? ""
                        : plan.framework()
                                .toLowerCase(
                                        Locale.ROOT
                                );

        return framework.contains(
                "mockito"
        );
    }

    private String packageNameFromPath(
            String proposedPath
    ) {
        String normalized =
                normalizeRelativePath(
                        proposedPath
                );

        String prefix =
                "src/test/java/";

        if (!normalized.startsWith(prefix)) {
            return "";
        }

        String withoutPrefix =
                normalized.substring(
                        prefix.length()
                );

        int lastSlash =
                withoutPrefix.lastIndexOf('/');

        if (lastSlash < 0) {
            return "";
        }

        return withoutPrefix
                .substring(
                        0,
                        lastSlash
                )
                .replace(
                        '/',
                        '.'
                );
    }

    private void validatePlan(
            RegressionTestPlan plan
    ) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Regression test plan is null."
            );
        }

        String normalized =
                normalizeRelativePath(
                        plan.proposedFilePath()
                );

        if (!normalized.startsWith(
                "src/test/java/"
        )) {
            throw new IllegalArgumentException(
                    "Only Java test paths under "
                            + "src/test/java are supported: "
                            + normalized
            );
        }

        if (!normalized.endsWith(
                ".java"
        )) {
            throw new IllegalArgumentException(
                    "Generated regression test must be a Java file."
            );
        }

        if (normalized.contains("../")
                || normalized.startsWith("/")
                || normalized.contains(":/")) {
            throw new IllegalArgumentException(
                    "Generated path is unsafe: "
                            + normalized
            );
        }

        if (!plan.humanApprovalRequired()) {
            throw new IllegalArgumentException(
                    "Plan must require human approval."
            );
        }
    }

    private String normalizeRelativePath(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value.replace(
                '\\',
                '/'
        ).trim();
    }

    private String sanitizeJavaIdentifier(
            String value,
            String fallback
    ) {
        String candidate =
                value == null
                        ? ""
                        : value.replaceAll(
                                "[^A-Za-z0-9_$]",
                                ""
                        );

        if (candidate.isBlank()) {
            return fallback;
        }

        if (!Character.isJavaIdentifierStart(
                candidate.charAt(0)
        )) {
            return fallback;
        }

        for (int index = 1;
             index < candidate.length();
             index++) {

            if (!Character.isJavaIdentifierPart(
                    candidate.charAt(index)
            )) {
                return fallback;
            }
        }

        return candidate;
    }

    private String safeComment(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("*/", "* /")
                .trim();
    }

    private String escapeJava(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
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

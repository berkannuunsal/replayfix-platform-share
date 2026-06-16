package com.etiya.replayfix.service;

import com.etiya.replayfix.model.LocalTestExecutionStatus;
import com.etiya.replayfix.model.RegressionTestPlan;
import com.etiya.replayfix.model.SafeProcessResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class LocalTestExecutionClassifier {

    public Classification classify(
            SafeProcessResult process,
            RegressionTestPlan plan
    ) {
        List<String> matchedSignals =
                new ArrayList<>();

        String output =
                process.output() == null
                        ? ""
                        : process.output();

        String normalized =
                output.toLowerCase(
                        Locale.ROOT
                );

        if (process.timedOut()) {
            return new Classification(
                    LocalTestExecutionStatus.TIMEOUT,
                    false,
                    false,
                    matchedSignals
            );
        }

        if (containsAny(
                normalized,
                "compilation failure",
                "compilation error",
                "cannot find symbol",
                "failed to execute goal"
                        + " org.apache.maven.plugins:"
                        + "maven-compiler-plugin"
        )) {
            matchedSignals.add(
                    "Maven compiler failure detected."
            );

            return new Classification(
                    LocalTestExecutionStatus.COMPILE_FAILURE,
                    false,
                    false,
                    matchedSignals
            );
        }

        if (containsAny(
                normalized,
                "no tests were executed",
                "no tests matching pattern",
                "no tests found"
        )) {
            matchedSignals.add(
                    "Generated test selector was not found."
            );

            return new Classification(
                    LocalTestExecutionStatus.TEST_NOT_FOUND,
                    false,
                    false,
                    matchedSignals
            );
        }

        if (process.exitCode() != null
                && process.exitCode() == 0) {
            matchedSignals.add(
                    "Maven exited with code 0."
            );

            return new Classification(
                    LocalTestExecutionStatus
                            .TEST_PASSED_UNEXPECTED_BEFORE_FIX,
                    false,
                    false,
                    matchedSignals
            );
        }

        if (containsAny(
                normalized,
                "unsupportedoperationexception",
                "replayfix regression scaffold"
        )) {
            matchedSignals.add(
                    "Generated scaffold failure detected."
            );

            return new Classification(
                    LocalTestExecutionStatus
                            .SCAFFOLD_FAILURE_NOT_REPRODUCED,
                    false,
                    true,
                    matchedSignals
            );
        }

        List<String> expectedSignals =
                expectedFailureSignals(plan);

        for (String signal : expectedSignals) {
            if (normalized.contains(
                    signal.toLowerCase(
                            Locale.ROOT
                    )
            )) {
                matchedSignals.add(
                        signal
                );
            }
        }

        if (!matchedSignals.isEmpty()) {
            return new Classification(
                    LocalTestExecutionStatus.DEFECT_REPRODUCED,
                    true,
                    false,
                    matchedSignals
            );
        }

        if (containsAny(
                normalized,
                "connection refused",
                "could not transfer artifact",
                "unknown host",
                "service unavailable"
        )) {
            matchedSignals.add(
                    "Infrastructure/dependency failure detected."
            );

            return new Classification(
                    LocalTestExecutionStatus
                            .INFRASTRUCTURE_FAILURE,
                    false,
                    false,
                    matchedSignals
            );
        }

        return new Classification(
                LocalTestExecutionStatus
                        .TEST_FAILURE_UNCLASSIFIED,
                false,
                false,
                matchedSignals
        );
    }

    private List<String> expectedFailureSignals(
            RegressionTestPlan plan
    ) {
        List<String> signals =
                new ArrayList<>();

        addSignal(
                signals,
                plan.expectedFailureBeforeFix()
        );

        if (plan.requestData() != null) {
            plan.requestData()
                    .forEach((key, value) -> {
                        addSignal(signals, key);
                        addSignal(signals, value);
                    });
        }

        if (plan.expectedResult() != null) {
            plan.expectedResult()
                    .forEach((key, value) -> {
                        addSignal(signals, key);
                        addSignal(signals, value);
                    });
        }

        String combined =
                String.join(
                        " ",
                        signals
                ).toLowerCase(
                        Locale.ROOT
                );

        if (combined.contains("401")
                || combined.contains(
                        "unauthorized"
                )) {
            signals.add("401");
            signals.add("unauthorized");
        }

        if (combined.contains("status 12")) {
            signals.add("status 12");
        }

        if (combined.contains("9999")) {
            signals.add("9999");
        }

        return signals.stream()
                .filter(value ->
                        value != null
                                && !value.isBlank()
                                && value.length() <= 200
                )
                .distinct()
                .toList();
    }

    private void addSignal(
            List<String> values,
            String value
    ) {
        if (value != null
                && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private boolean containsAny(
            String value,
            String... expected
    ) {
        for (String item : expected) {
            if (value.contains(
                    item.toLowerCase(
                            Locale.ROOT
                    )
            )) {
                return true;
            }
        }

        return false;
    }

    public record Classification(
            LocalTestExecutionStatus status,
            boolean defectReproduced,
            boolean scaffoldFailure,
            List<String> matchedSignals
    ) {
    }
}

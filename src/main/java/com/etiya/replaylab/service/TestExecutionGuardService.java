package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.TestExecutionApprovalRequest;
import com.etiya.replaylab.api.dto.TestExecutionGuardResponse;
import com.etiya.replaylab.api.dto.TestExecutionPlanResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class TestExecutionGuardService {

    private static final List<String> GUARDRAILS = List.of(
            "NO_AUTO_TEST_EXECUTION",
            "HUMAN_APPROVAL_REQUIRED",
            "WORKSPACE_ONLY",
            "NO_JENKINS",
            "NO_PR",
            "NO_BRANCH",
            "NO_ARGOCD_SYNC",
            "NO_SECRET_EXPOSURE",
            "EXECUTION_DISABLED"
    );

    private final TestExecutionPlanService testExecutionPlanService;

    public TestExecutionGuardService(
            TestExecutionPlanService testExecutionPlanService
    ) {
        this.testExecutionPlanService = testExecutionPlanService;
    }

    @Transactional(readOnly = true)
    public TestExecutionGuardResponse preview(UUID caseId, boolean dryRun) {
        TestExecutionPlanResponse plan = testExecutionPlanService.plan(
                caseId,
                true,
                true,
                true
        );
        List<String> warnings = new ArrayList<>(plan.warnings());
        if (!dryRun) {
            warnings.add("PREVIEW_ENDPOINT_FORCES_DRY_RUN");
        }
        return response(
                plan,
                "PREVIEW_READY",
                true,
                false,
                plan.blockers(),
                warnings
        );
    }

    @Transactional(readOnly = true)
    public TestExecutionGuardResponse apply(
            UUID caseId,
            TestExecutionApprovalRequest approval,
            boolean dryRun
    ) {
        TestExecutionPlanResponse plan = testExecutionPlanService.plan(
                caseId,
                true,
                true,
                true
        );
        boolean approvalPresent = approvalPresent(approval);
        List<String> blockers = new ArrayList<>(plan.blockers());
        List<String> warnings = new ArrayList<>(plan.warnings());

        if (!approvalPresent) {
            blockers.add("HUMAN_APPROVAL_REQUIRED");
            warnings.add("APPROVAL_REQUIRED_BEFORE_TEST_EXECUTION");
            return response(
                    plan,
                    "BLOCKED_BY_MISSING_APPROVAL",
                    dryRun,
                    false,
                    blockers,
                    warnings
            );
        }
        if (dryRun) {
            warnings.add("DRY_RUN_TRUE_NO_TEST_EXECUTION");
            return response(
                    plan,
                    "DRY_RUN_READY",
                    true,
                    true,
                    blockers,
                    warnings
            );
        }

        blockers.add("TEST_EXECUTION_NOT_ENABLED");
        warnings.add("REAL_TEST_EXECUTION_DISABLED_FOR_FOUNDATION");
        return response(
                plan,
                "BLOCKED_BY_EXECUTION_NOT_ENABLED",
                false,
                true,
                blockers,
                warnings
        );
    }

    private TestExecutionGuardResponse response(
            TestExecutionPlanResponse plan,
            String executionStatus,
            boolean dryRun,
            boolean approvalPresent,
            List<String> blockers,
            List<String> warnings
    ) {
        return new TestExecutionGuardResponse(
                plan.caseId(),
                plan.jiraKey(),
                "HYPOTHESIS",
                executionStatus,
                dryRun,
                true,
                approvalPresent,
                false,
                plan.testCommands(),
                plan.testTargets(),
                unique(blockers),
                GUARDRAILS,
                unique(warnings),
                Instant.now()
        );
    }

    private boolean approvalPresent(TestExecutionApprovalRequest approval) {
        return approval != null
                && approval.acceptedGuardrails()
                && !isBlank(approval.approvedBy());
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()
        ));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

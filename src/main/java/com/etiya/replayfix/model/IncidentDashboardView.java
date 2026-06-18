package com.etiya.replayfix.model;

import java.util.List;

public record IncidentDashboardView(
        CaseSummaryView caseSummary,
        WorkflowRunView workflow,
        List<DashboardEvidenceCard> evidenceCards,
        RootCauseDashboardView rootCause,
        RovoRcaDashboardView rovoRca,
        RegressionTestHypothesisDashboardView regressionTestHypothesis,
        FailingRegressionTestDraftDashboardView failingRegressionTestDraft,
        List<MissingEvidenceView> missingEvidence,
        JiraEvidenceCommentPreview jiraPreview,
        List<ApprovalRequestView> approvals,
        List<AuditEventView> auditEvents,
        DashboardPolicyView policies,
        String generatedAt
) {
}

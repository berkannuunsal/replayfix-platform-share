package com.etiya.replayfix.workflow.handlers;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.JiraEvidenceCommentPreview;
import com.etiya.replayfix.service.JiraEvidenceCommentPreviewService;
import com.etiya.replayfix.workflow.WorkflowContext;
import com.etiya.replayfix.workflow.WorkflowStepExecutionResult;
import com.etiya.replayfix.workflow.WorkflowStepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CreateJiraEvidencePreviewStepHandler implements WorkflowStepHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateJiraEvidencePreviewStepHandler.class);

    private final JiraEvidenceCommentPreviewService previewService;
    private final ReplayFixProperties properties;

    public CreateJiraEvidencePreviewStepHandler(
            JiraEvidenceCommentPreviewService previewService,
            ReplayFixProperties properties
    ) {
        this.previewService = previewService;
        this.properties = properties;
    }

    @Override
    public String stepName() {
        return "CREATE_JIRA_EVIDENCE_PREVIEW";
    }

    @Override
    public boolean isEnabled(WorkflowContext context) {
        return properties.getIntegrations().getJiraWebhook().isAutoPreviewEnabled();
    }

    @Override
    public WorkflowStepExecutionResult execute(WorkflowContext context) {
        try {
            log.info("Creating Jira evidence preview for case {}", context.caseId());

            JiraEvidenceCommentPreview preview = previewService.createPreview(context.caseId());

            log.info("Jira evidence preview created: {}", preview.previewEvidenceId());

            return WorkflowStepExecutionResult.success(
                    "REPLAY_OUTPUT",
                    "jira-evidence-summary-preview",
                    preview.previewEvidenceId(),
                    "Jira evidence preview created; human approval required for publication"
            );

        } catch (IllegalStateException e) {
            log.warn("Preview creation skipped: {}", e.getMessage());
            return WorkflowStepExecutionResult.skipped(e.getMessage());

        } catch (Exception e) {
            log.error("Failed to create Jira evidence preview", e);
            return WorkflowStepExecutionResult.failed(
                    "PREVIEW_CREATION_ERROR",
                    "Failed to create preview: " + e.getMessage()
            );
        }
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public int maxAttempts() {
        return 2;
    }

    @Override
    public int sequenceNumber() {
        return 16;
    }
}

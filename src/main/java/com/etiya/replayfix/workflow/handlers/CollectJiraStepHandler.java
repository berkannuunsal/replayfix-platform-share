package com.etiya.replayfix.workflow.handlers;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.service.JiraEvidenceCollectionService;
import com.etiya.replayfix.workflow.WorkflowContext;
import com.etiya.replayfix.workflow.WorkflowStepExecutionResult;
import com.etiya.replayfix.workflow.WorkflowStepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CollectJiraStepHandler implements WorkflowStepHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectJiraStepHandler.class);

    private final JiraEvidenceCollectionService jiraCollectionService;
    private final ReplayFixProperties properties;

    public CollectJiraStepHandler(
            JiraEvidenceCollectionService jiraCollectionService,
            ReplayFixProperties properties
    ) {
        this.jiraCollectionService = jiraCollectionService;
        this.properties = properties;
    }

    @Override
    public String stepName() {
        return "COLLECT_JIRA";
    }

    @Override
    public boolean isEnabled(WorkflowContext context) {
        return properties.getIntegrations().getJira().isEnabled();
    }

    @Override
    public WorkflowStepExecutionResult execute(WorkflowContext context) {
        try {
            log.info("Collecting Jira evidence for issue {}", context.issueKey());

            EvidenceEntity evidence = jiraCollectionService.collectJiraEvidence(
                    context.caseId(),
                    context.issueKey()
            );

            return WorkflowStepExecutionResult.success(
                    evidence.getEvidenceType().name(),
                    evidence.getSource(),
                    evidence.getId(),
                    "Jira issue collected"
            );

        } catch (Exception e) {
            log.error("Failed to collect Jira evidence", e);
            return WorkflowStepExecutionResult.failed(
                    "JIRA_COLLECTION_ERROR",
                    "Failed to collect Jira: " + e.getMessage()
            );
        }
    }

    @Override
    public boolean isRequired() {
        return true;
    }

    @Override
    public int maxAttempts() {
        return 3;
    }

    @Override
    public int sequenceNumber() {
        return 1;
    }
}

package com.etiya.replayfix.workflow;

public interface WorkflowStepHandler {
    
    String stepName();
    
    boolean isEnabled(WorkflowContext context);
    
    WorkflowStepExecutionResult execute(WorkflowContext context);
    
    boolean isRequired();
    
    int maxAttempts();
    
    int sequenceNumber();
}

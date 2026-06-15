package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.JiraIssue;

public interface JiraClient {
    JiraIssue getIssue(String issueKey);
    void addComment(String issueKey, String text);
}

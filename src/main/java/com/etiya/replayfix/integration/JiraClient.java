package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.etiya.replayfix.model.JiraCommentPublishResponse;
import com.fasterxml.jackson.databind.JsonNode;

public interface JiraClient {
    JiraIssue getIssue(String issueKey);
    void addComment(String issueKey, String text);
    JiraCommentPublishResponse addCommentAdf(String issueKey, JsonNode adfBody);
}

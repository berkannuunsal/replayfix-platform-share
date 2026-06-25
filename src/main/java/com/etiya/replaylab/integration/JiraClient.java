package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import com.etiya.replaylab.model.IntegrationModels.JiraComment;
import com.etiya.replaylab.model.IntegrationModels.JiraIssueCreateResult;
import com.etiya.replaylab.model.JiraCommentPublishResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface JiraClient {
    JiraIssue getIssue(String issueKey);
    JiraIssueCreateResult createIssue(Map<String, Object> payload);
    void addComment(String issueKey, String text);
    JiraCommentPublishResponse addCommentAdf(String issueKey, JsonNode adfBody);
    List<JiraComment> getComments(String issueKey);
}

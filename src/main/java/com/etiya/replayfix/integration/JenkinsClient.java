package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import com.etiya.replayfix.model.JenkinsConnectionTestResult;
import com.etiya.replayfix.model.JenkinsJobSnapshot;

import java.util.Map;

public interface JenkinsClient {
    JenkinsConnectionTestResult testConnection();

    JenkinsJobSnapshot readJob(String jobUrl);

    BuildResult runValidation(Map<String, String> parameters);
}

package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsConnectionTestResult;
import com.etiya.replayfix.model.JenkinsJobSnapshot;

import java.time.Instant;
import java.util.Map;

public interface JenkinsClient {
    JenkinsConnectionTestResult testConnection();

    JenkinsJobSnapshot readJob(String jobUrl);

    JenkinsBuildSnapshot readLastSuccessfulBuild(String jobUrl);

    JenkinsBuildSnapshot readBuildAtOrBefore(
            String jobUrl,
            Instant incidentTime
    );

    BuildResult runValidation(Map<String, String> parameters);
}

package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.IntegrationModels.BuildResult;
import com.etiya.replaylab.model.IntegrationModels.JenkinsBuildStatus;
import com.etiya.replaylab.model.IntegrationModels.JenkinsQueueItem;
import com.etiya.replaylab.model.IntegrationModels.JenkinsTriggerResult;
import com.etiya.replaylab.model.JenkinsBuildSnapshot;
import com.etiya.replaylab.model.JenkinsConnectionTestResult;
import com.etiya.replaylab.model.JenkinsJobSnapshot;

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

    default JenkinsTriggerResult triggerValidation(
            String jobName,
            Map<String, String> parameters
    ) {
        BuildResult result = runValidation(parameters);
        return new JenkinsTriggerResult(
                !"FAILED".equalsIgnoreCase(result.status()),
                "",
                result.buildUrl(),
                result.status(),
                java.util.List.of()
        );
    }

    default JenkinsQueueItem getQueueItem(String queueUrl) {
        return new JenkinsQueueItem(queueUrl, false, "", "", java.util.List.of("JENKINS_QUEUE_READ_NOT_IMPLEMENTED"));
    }

    default JenkinsBuildStatus getBuildStatus(String jobName, String buildNumber) {
        return new JenkinsBuildStatus("UNKNOWN", false, buildNumber, "", 0, 0,
                java.util.List.of("JENKINS_BUILD_STATUS_READ_NOT_IMPLEMENTED"));
    }

    default JenkinsBuildStatus getBuildStatusByUrl(String buildUrl) {
        return new JenkinsBuildStatus("UNKNOWN", false, "", buildUrl, 0, 0,
                java.util.List.of("JENKINS_BUILD_STATUS_READ_NOT_IMPLEMENTED"));
    }
}

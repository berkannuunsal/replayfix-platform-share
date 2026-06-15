package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import java.util.Map;

public interface JenkinsClient {
    BuildResult runValidation(Map<String, String> parameters);
}

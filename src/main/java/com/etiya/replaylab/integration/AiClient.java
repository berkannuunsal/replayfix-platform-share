package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.AiConnectionTestResult;
import com.etiya.replaylab.model.IntegrationModels.GenerationResult;
import com.etiya.replaylab.model.IntegrationModels.RootCauseResult;

public interface AiClient {

    AiConnectionTestResult testConnection();

    RootCauseResult analyzeRootCause(
            String evidenceJson
    );

    GenerationResult generateRegressionTest(
            String evidenceJson,
            String sourceContext
    );

    GenerationResult generatePatch(
            String evidenceJson,
            String sourceContext,
            String testFailure
    );
}

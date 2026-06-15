package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.AiConnectionTestResult;
import com.etiya.replayfix.model.IntegrationModels.GenerationResult;
import com.etiya.replayfix.model.IntegrationModels.RootCauseResult;

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

package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.AiConnectionTestResult;
import com.etiya.replayfix.model.IntegrationModels.GeneratedFile;
import com.etiya.replayfix.model.IntegrationModels.GenerationResult;
import com.etiya.replayfix.model.IntegrationModels.RootCauseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient implements AiClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(
            ReplayFixProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConnectionTestResult testConnection() {
        var cfg = properties.getIntegrations().getAi();

        if (!cfg.isEnabled()) {
            return new AiConnectionTestResult(
                    false,
                    cfg.getModel(),
                    0,
                    "AI integration is disabled."
            );
        }

        long startedAt = System.currentTimeMillis();

        try {
            JsonNode response = chat("""
                    Return strict JSON only:

                    {
                      "ok": true,
                      "message": "ReplayLab AI connectivity test"
                    }

                    Do not include markdown or additional explanation.
                    """);

            JsonNode content = parseContent(response);

            boolean success =
                    content.path("ok").asBoolean(false);

            String message =
                    content.path("message")
                            .asText("AI endpoint responded.");

            return new AiConnectionTestResult(
                    success,
                    cfg.getModel(),
                    System.currentTimeMillis() - startedAt,
                    message
            );

        } catch (Exception exception) {
            return new AiConnectionTestResult(
                    false,
                    cfg.getModel(),
                    System.currentTimeMillis() - startedAt,
                    rootCauseMessage(exception)
            );
        }
    }

    @Override
    public RootCauseResult analyzeRootCause(String evidenceJson) {
        if (!properties.getIntegrations().getAi().isEnabled()) {
            return new RootCauseResult(
                    "AI analysis is disabled",
                    "The deterministic ReplayLab report remains the active hypothesis.",
                    0.0,
                    List.of(
                            "AI_INPUT_BUNDLE was generated but not sent to a model."
                    ),
                    List.of(
                            "Review the deterministic root-cause report.",
                            "Enable the approved internal AI endpoint when available."
                    ),
                    "{}"
            );
        }

        String prompt = """
You are reviewing a structured software incident evidence bundle.

Return strict JSON only:

{
  "summary": "...",
  "probableRootCause": "...",
  "confidence": 0.0,
  "evidence": [
    "..."
  ],
  "remediationActions": [
    "..."
  ]
}

Rules:

1. Use only the supplied evidence bundle.
2. Do not invent source files, line numbers, services, deployments,
   database records or configuration values.
3. The probable root cause must remain a hypothesis until a regression
   test reproduces the failure.
4. If Loki, Tempo or source-code evidence is missing, explicitly state
   that limitation.
5. If the evidence is contradictory, lower confidence and describe the
   contradiction.
6. Evidence items must refer to concrete sections in the bundle, such as:
   deterministicReport, timelineEvents, correlations, tempo or knowledge.
7. Do not recommend automatic merge or production deployment.
8. Human approval is mandatory.
9. Return confidence between 0.0 and 0.95.
10. If no matching logs and no trace are available, confidence must not
    exceed 0.60.

Evidence bundle:

""" + evidenceJson;

        JsonNode response = chat(prompt);
        JsonNode content = parseContent(response);
        return new RootCauseResult(
                content.path("summary").asText(""),
                content.path("probableRootCause").asText(""),
                content.path("confidence").asDouble(0.0),
                toStrings(content.path("evidence")),
                toStrings(content.path("remediationActions")),
                response.toString()
        );
    }

    @Override
    public GenerationResult generateRegressionTest(String evidenceJson, String sourceContext) {
        if (!properties.getIntegrations().getAi().isEnabled()) {
            String sample = "package com.example.replayfix;\\n\\n" +
                    "import org.junit.jupiter.api.Test;\\n" +
                    "import static org.junit.jupiter.api.Assertions.assertTrue;\\n\\n" +
                    "class ReplayLabGeneratedRegressionTest {\\n" +
                    "    @Test void incident_is_reproducible() { assertTrue(true); }\\n" +
                    "}\\n";
            return new GenerationResult(
                    List.of(new GeneratedFile(
                            "src/test/java/com/example/replayfix/ReplayLabGeneratedRegressionTest.java",
                            sample
                    )),
                    "Dry-run generated regression test",
                    "{}"
            );
        }

        String prompt = "Generate one minimal regression test. Return strict JSON: " +
                "{\\\"explanation\\\":\\\"...\\\",\\\"files\\\":[{\\\"path\\\":\\\"...\\\",\\\"content\\\":\\\"...\\\"}]}. " +
                "Evidence: " + evidenceJson + " Source context: " + sourceContext;
        return generation(chat(prompt));
    }

    @Override
    public GenerationResult generatePatch(String evidenceJson, String sourceContext, String testFailure) {
        if (!properties.getIntegrations().getAi().isEnabled()) {
            return new GenerationResult(
                    List.of(),
                    "Dry-run patch generation intentionally produced no source modification.",
                    "{}"
            );
        }

        String prompt = "Generate the minimum safe patch. Return strict JSON: " +
                "{\\\"explanation\\\":\\\"...\\\",\\\"files\\\":[{\\\"path\\\":\\\"...\\\",\\\"content\\\":\\\"...\\\"}]}. " +
                "Evidence: " + evidenceJson + " Source context: " + sourceContext +
                " Test failure: " + testFailure;
        return generation(chat(prompt));
    }

    private JsonNode chat(String prompt) {
        var cfg = properties.getIntegrations().getAi();

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "model",
                cfg.getModel()
        );

        request.put(
                "temperature",
                cfg.getTemperature()
        );

        request.put(
                "max_tokens",
                cfg.getMaxOutputTokens()
        );

        request.put(
                "messages",
                List.of(
                        Map.of(
                                "role",
                                "system",
                                "content",
                                "You are ReplayLab. "
                                        + "Return strict JSON only."
                        ),
                        Map.of(
                                "role",
                                "user",
                                "content",
                                prompt
                        )
                )
        );

        JsonNode response = webClientBuilder
                .baseUrl(
                        cfg.getBaseUrl()
                                .replaceAll("/+$", "")
                )
                .build()
                .post()
                .uri(cfg.getChatPath())
                .headers(headers ->
                        headers.addAll(
                                HttpSupport.headers(cfg)
                        )
                )
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse ->
                                clientResponse
                                        .bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .map(body ->
                                                new IllegalStateException(
                                                        "AI request failed. HTTP "
                                                                + clientResponse
                                                                .statusCode()
                                                                + " Response: "
                                                                + truncate(
                                                                body,
                                                                2_000
                                                        )
                                                )
                                        )
                )
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        if (response == null) {
            throw new IllegalStateException(
                    "AI endpoint returned an empty response"
            );
        }

        return response;
    }

    private JsonNode parseContent(JsonNode response) {
        JsonNode contentNode = response
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

        if (contentNode.isObject()) {
            return contentNode;
        }

        String content = contentNode.asText("");

        if (content.isBlank()
                && response.has("output_text")) {
            content = response
                    .path("output_text")
                    .asText("");
        }

        if (content.isBlank()
                && response.has("summary")) {
            return response;
        }

        content = content.trim()
                .replaceFirst(
                        "^```json\\s*",
                        ""
                )
                .replaceFirst(
                        "^```\\s*",
                        ""
                )
                .replaceFirst(
                        "\\s*```$",
                        ""
                )
                .trim();

        try {
            return objectMapper.readTree(content);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "AI response is not valid JSON: "
                            + truncate(content, 2_000),
                    exception
            );
        }
    }

    private GenerationResult generation(JsonNode response) {
        JsonNode content = parseContent(response);
        List<GeneratedFile> files = new ArrayList<>();
        for (JsonNode file : content.path("files")) {
            files.add(new GeneratedFile(
                    file.path("path").asText(),
                    file.path("content").asText()
            ));
        }
        return new GenerationResult(
                files,
                content.path("explanation").asText(""),
                response.toString()
        );
    }

    private List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private String rootCauseMessage(
            Throwable throwable
    ) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
                + ": "
                + root.getMessage();
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}

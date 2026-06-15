package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.GeneratedFile;
import com.etiya.replayfix.model.IntegrationModels.GenerationResult;
import com.etiya.replayfix.model.IntegrationModels.RootCauseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
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
    public RootCauseResult analyzeRootCause(String evidenceJson) {
        if (!properties.getIntegrations().getAi().isEnabled()) {
            return new RootCauseResult(
                    "Dry-run root cause analysis",
                    "Enable the internal AI endpoint for code-aware analysis.",
                    0.50,
                    List.of("Dry-run Jira issue", "Dry-run Loki log"),
                    List.of("Create a deterministic regression test", "Review validation and null paths"),
                    "{}"
            );
        }

        String prompt = "Analyze the incident evidence and return strict JSON with " +
                "summary, probableRootCause, confidence, evidence[] and remediationActions[]. Evidence: " +
                evidenceJson;

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
                    "class ReplayFixGeneratedRegressionTest {\\n" +
                    "    @Test void incident_is_reproducible() { assertTrue(true); }\\n" +
                    "}\\n";
            return new GenerationResult(
                    List.of(new GeneratedFile(
                            "src/test/java/com/example/replayfix/ReplayFixGeneratedRegressionTest.java",
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
        Map<String, Object> request = Map.of(
                "model", cfg.getModel(),
                "temperature", cfg.getTemperature(),
                "max_tokens", cfg.getMaxOutputTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", "You are ReplayFix. Return strict JSON only."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        JsonNode node = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().post()
                .uri(cfg.getChatPath())
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        if (node == null) throw new IllegalStateException("AI endpoint returned an empty response");
        return node;
    }

    private JsonNode parseContent(JsonNode response) {
        String content = response.path("choices").path(0).path("message").path("content").asText("{}");
        content = content.replaceFirst("^```json\\\\s*", "").replaceFirst("```$", "");
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("AI response is not valid JSON: " + content, e);
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
}

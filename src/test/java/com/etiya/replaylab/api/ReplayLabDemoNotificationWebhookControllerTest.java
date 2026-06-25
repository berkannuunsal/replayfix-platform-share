package com.etiya.replaylab.api;

import com.etiya.replaylab.model.DemoWebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
class ReplayLabDemoNotificationWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String SECRET = "demo-secret-key";

    @Test
    void testValidHmacSignature_Returns204() throws Exception {
        String payload = """
                {
                    "eventType": "WORKFLOW_SUCCESS",
                    "notificationId": "123",
                    "caseId": "456",
                    "jiraKey": "TEST-1",
                    "title": "Test notification",
                    "severity": "INFO"
                }
                """;

        String signature = computeHmacSha256(payload, SECRET);

        mockMvc.perform(post("/api/v1/demo/notifications/webhook-receiver")
                        .header("X-ReplayLab-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());
    }

    @Test
    void testInvalidHmacSignature_Returns401() throws Exception {
        String payload = """
                {
                    "eventType": "WORKFLOW_SUCCESS",
                    "notificationId": "123",
                    "caseId": "456",
                    "jiraKey": "TEST-1",
                    "title": "Test notification",
                    "severity": "INFO"
                }
                """;

        String invalidSignature = "invalid-signature-12345";

        mockMvc.perform(post("/api/v1/demo/notifications/webhook-receiver")
                        .header("X-ReplayLab-Signature", invalidSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testMissingSignatureHeader_Returns401() throws Exception {
        String payload = """
                {
                    "eventType": "WORKFLOW_SUCCESS",
                    "notificationId": "123",
                    "caseId": "456",
                    "jiraKey": "TEST-1",
                    "title": "Test notification",
                    "severity": "INFO"
                }
                """;

        mockMvc.perform(post("/api/v1/demo/notifications/webhook-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testGetReceivedEvents_ReturnsEvents() throws Exception {
        String payload = """
                {
                    "eventType": "WORKFLOW_PARTIAL_SUCCESS",
                    "notificationId": "789",
                    "caseId": "abc",
                    "jiraKey": "TEST-2",
                    "title": "Partial success",
                    "severity": "WARNING"
                }
                """;

        String signature = computeHmacSha256(payload, SECRET);

        mockMvc.perform(post("/api/v1/demo/notifications/webhook-receiver")
                        .header("X-ReplayLab-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(get("/api/v1/demo/notifications/webhook-receiver/events"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        List<DemoWebhookEvent> events = objectMapper.readValue(
                responseBody,
                objectMapper.getTypeFactory().constructCollectionType(List.class, DemoWebhookEvent.class)
        );

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).eventType()).isEqualTo("WORKFLOW_PARTIAL_SUCCESS");
        assertThat(events.get(0).jiraKey()).isEqualTo("TEST-2");
        assertThat(events.get(0).signatureValid()).isTrue();
    }

    @Test
    void testSignatureMasking_DoesNotLeakSecret() throws Exception {
        String payload = """
                {
                    "eventType": "WORKFLOW_SUCCESS",
                    "notificationId": "999",
                    "caseId": "xyz",
                    "jiraKey": "TEST-3",
                    "title": "Test",
                    "severity": "INFO"
                }
                """;

        String signature = computeHmacSha256(payload, SECRET);

        mockMvc.perform(post("/api/v1/demo/notifications/webhook-receiver")
                        .header("X-ReplayLab-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(get("/api/v1/demo/notifications/webhook-receiver/events"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        
        assertThat(responseBody).doesNotContain(SECRET);
        assertThat(responseBody).contains("...");
    }

    private String computeHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hmacBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}

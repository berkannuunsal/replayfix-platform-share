package com.etiya.replayfix.api;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.etiya.replayfix.service.ai.MockAiProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "replayfix.ai.enabled=true",
        "replayfix.ai.provider=MOCK",
        "replayfix.ai.model=mock-replayfix-v1"
})
class AiIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testConnectivity_MockProvider_Success() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/integrations/ai/connectivity"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AiConnectivityResult connectivity = objectMapper.readValue(responseBody, AiConnectivityResult.class);

        assertThat(connectivity.success()).isTrue();
        assertThat(connectivity.enabled()).isTrue();
        assertThat(connectivity.provider()).isEqualTo("MOCK");
        assertThat(connectivity.model()).isEqualTo("mock-replayfix-v1");
        assertThat(connectivity.warnings()).contains("Synthetic AI provider for local validation.");
    }
}

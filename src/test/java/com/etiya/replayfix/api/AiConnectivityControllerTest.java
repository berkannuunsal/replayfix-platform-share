package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.AiClient;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiConnectivityControllerTest {

    @Test
    void shouldExposeProviderConnectivity() {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        when(provider.connectivity(eq(null), eq(null)))
                .thenReturn(new AiConnectivityResult(
                true,
                true,
                "COMPANY_LLM",
                "company-model",
                true,
                true,
                true,
                true,
                true,
                200,
                12L,
                null,
                List.of()
        ));

        AiConnectivityResult result = new AiConnectivityController(
                legacyClient,
                factory
        ).connectivity().block();

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("COMPANY_LLM");
        assertThat(result.baseUrlConfigured()).isTrue();
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.reachable()).isTrue();
    }

    @Test
    void shouldForwardModelProfileAndModelName() {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        when(provider.connectivity(
                eq("CODE_ADVISORY"),
                eq("openai/gpt-4o-mini")
        )).thenReturn(new AiConnectivityResult(
                true,
                true,
                "LITELLM_OPENAI_COMPATIBLE",
                "openai/gpt-4o-mini",
                true,
                true,
                true,
                true,
                true,
                200,
                12L,
                null,
                "CODE_ADVISORY",
                "openai/gpt-4o-mini",
                90,
                12000,
                3000,
                true,
                "WEEKLY",
                200.0,
                false,
                0,
                0,
                0,
                List.of()
        ));

        AiConnectivityResult result = new AiConnectivityController(
                legacyClient,
                factory
        ).connectivity("CODE_ADVISORY", "openai/gpt-4o-mini").block();

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("LITELLM_OPENAI_COMPATIBLE");
        assertThat(result.modelProfile()).isEqualTo("CODE_ADVISORY");
        assertThat(result.effectiveModelName())
                .isEqualTo("openai/gpt-4o-mini");
        verify(provider).connectivity(
                "CODE_ADVISORY",
                "openai/gpt-4o-mini"
        );
    }

    @Test
    void connectivityEndpointRejectsPlainLiteLlmModelName()
            throws Exception {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        doThrow(new IllegalArgumentException(
                "MODEL_NAME_MUST_USE_OPENAI_PREFIX"
        )).when(provider).connectivity(null, "gpt-3.5-turbo");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AiConnectivityController(
                        legacyClient,
                        factory
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mockMvc.perform(get("/api/v1/ai/connectivity")
                        .param("modelName", "gpt-3.5-turbo"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("MODEL_NAME_MUST_USE_OPENAI_PREFIX"));
    }

    @Test
    void connectivityEndpointAcceptsAllowedOpenAiPrefixedModelName()
            throws Exception {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        when(provider.connectivity(null, "openai/gpt-3.5-turbo"))
                .thenReturn(new AiConnectivityResult(
                        true,
                        true,
                        "LITELLM_OPENAI_COMPATIBLE",
                        "openai/gpt-3.5-turbo",
                        true,
                        true,
                        true,
                        true,
                        true,
                        200,
                        12L,
                        null,
                        "CODE_ADVISORY",
                        "openai/gpt-3.5-turbo",
                        90,
                        12000,
                        3000,
                        true,
                        "WEEKLY",
                        200.0,
                        false,
                        0,
                        0,
                        0,
                        List.of()
                ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AiConnectivityController(
                        legacyClient,
                        factory
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mockMvc.perform(get("/api/v1/ai/connectivity")
                        .param("modelName", "openai/gpt-3.5-turbo"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveModelName")
                        .value("openai/gpt-3.5-turbo"))
                .andExpect(jsonPath("$.budgetTrackingEnabled").value(true))
                .andExpect(jsonPath("$.budgetPeriod").value("WEEKLY"))
                .andExpect(jsonPath("$.weeklyBudgetUsd").value(200.0));
    }

    @Test
    void connectivityEndpointRejectsUnknownOpenAiPrefixedModelName()
            throws Exception {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        doThrow(new IllegalArgumentException("MODEL_NOT_ALLOWED"))
                .when(provider).connectivity(null, "openai/unknown-model");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AiConnectivityController(
                        legacyClient,
                        factory
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mockMvc.perform(get("/api/v1/ai/connectivity")
                        .param("modelName", "openai/unknown-model"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("MODEL_NOT_ALLOWED"));
    }
}

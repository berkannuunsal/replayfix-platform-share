package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ReplayArgoCdInventoryContext;
import com.etiya.replaylab.api.dto.ReplayEnvironmentAccessRoutingPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentDbStrategyPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentDryRunBundle;
import com.etiya.replaylab.api.dto.ReplayEnvironmentNamespacePlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentReadiness;
import com.etiya.replaylab.api.dto.ReplayEnvironmentStateContinuationPlan;
import com.etiya.replaylab.service.ReplayEnvironmentPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayEnvironmentControllerTest {

    @Test
    void returns200ForExistingCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayEnvironmentPlanService service =
                mock(ReplayEnvironmentPlanService.class);
        when(service.plan(eq(caseId), anyBoolean(), eq("WIREMOCK")))
                .thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/replay-environment/plan",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.dryRunBundle.dryRunOnly").value(true));
    }

    @Test
    void dryRunFalseReturnsBadRequest() throws Exception {
        ReplayEnvironmentPlanService service =
                mock(ReplayEnvironmentPlanService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/replay-environment/plan",
                        UUID.randomUUID()
                ).param("dryRun", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "{\"message\":\"Real ArgoCD provisioning is not enabled yet. Use dryRun=true.\"}"
                ));

        verifyNoInteractions(service);
    }

    private ReplayEnvironmentPlanResponse response(UUID caseId) {
        ReplayEnvironmentComponentPlan backend =
                new ReplayEnvironmentComponentPlan(
                        "BACKEND",
                        "bss-backend",
                        "replay-fizzms-10228-backend",
                        "https://bitbucket.example.com/helm.git",
                        "bss-backend",
                        "test2",
                        "project-test2-values.yaml",
                        "project-test-bss-backend",
                        "registry.example.com/bss-backend",
                        "incident-10228",
                        "/actuator/health",
                        Map.of("image", Map.of("tag", "incident-10228")),
                        List.of(),
                        List.of(),
                        List.of()
                );
        return new ReplayEnvironmentPlanResponse(
                caseId,
                "FIZZMS-10228",
                "bss-backend",
                "PLAN_READY",
                "Plan ready",
                new ReplayArgoCdInventoryContext(
                        "project-test",
                        "EKS test2",
                        "https://kubernetes.default.svc",
                        List.of("bss-backend"),
                        Map.of()
                ),
                backend,
                null,
                new ReplayEnvironmentNamespacePlan(
                        "PRE_CREATED_NAMESPACE",
                        "replay-fizzms-10228",
                        "replay-fizzms-10228",
                        false,
                        false,
                        List.of()
                ),
                new ReplayEnvironmentDbStrategyPlan(
                        "TEST2_SHARED_DB",
                        true,
                        true,
                        false,
                        true,
                        true,
                        false,
                        List.of("BSS_DB_URL", "BSS_DB_USER", "BSS_DB_PASSWORD"),
                        List.of("customer_id"),
                        List.of(),
                        List.of("Confirm read-only DB user for replay validation")
                ),
                new ReplayEnvironmentAccessRoutingPlan(
                        "SINGLE_HOST_PATH_BASED",
                        "replay-fizzms-10228.example.test",
                        "/",
                        "/DCE-CommerceBackend",
                        "http://replay-fizzms-10228-backend.replay-fizzms-10228.svc.cluster.local:8080",
                        "https://replay-fizzms-10228.example.test/DCE-CommerceBackend",
                        true,
                        false,
                        false,
                        List.of("CUSTOMER_UI_BACKEND_BASE_URL"),
                        List.of(),
                        List.of(),
                        Map.of("dryRunOnly", true)
                ),
                new ReplayEnvironmentStateContinuationPlan(
                        true,
                        "TEST2_DB",
                        List.of("orderId"),
                        List.of("sanitized replay request payload"),
                        List.of(),
                        List.of("Validate sanitized replay input against state source TEST2_DB")
                ),
                List.of(),
                List.of(),
                new ReplayEnvironmentDryRunBundle(
                        true,
                        Map.of("kind", "Application"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()
                ),
                new ReplayEnvironmentReadiness(
                        true,
                        true,
                        false,
                        false,
                        List.of()
                ),
                List.of("Human approval before ArgoCD provisioning"),
                List.of(),
                List.of("dry-run first"),
                List.of("Review plan"),
                Instant.now()
        );
    }
}

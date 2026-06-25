package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayArgoCdInventoryContext;
import com.etiya.replaylab.api.dto.ReplayEnvironmentAccessRoutingPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentDbStrategyPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentDryRunBundle;
import com.etiya.replaylab.api.dto.ReplayEnvironmentNamespacePlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentProvisioningDisabledResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentReadiness;
import com.etiya.replaylab.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentStateContinuationPlan;
import com.etiya.replaylab.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replaylab.api.dto.ApproveReplayEnvironmentRequest;
import com.etiya.replaylab.api.dto.CreateReplayEnvironmentRequestResponse;
import com.etiya.replaylab.api.dto.RejectReplayEnvironmentRequest;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.ReplayEnvironmentRequestEntity;
import com.etiya.replaylab.repository.ReplayEnvironmentRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayEnvironmentRequestServiceTest {

    private ReplayEnvironmentPlanService planService;
    private ReplayEnvironmentRequestRepository repository;
    private ReplayEnvironmentRequestService service;
    private ObjectMapper objectMapper;
    private ReplayLabProperties properties;
    private UUID caseId;
    private UUID requestId;
    private AtomicReference<ReplayEnvironmentRequestEntity> savedEntity;

    @BeforeEach
    void setUp() {
        planService = mock(ReplayEnvironmentPlanService.class);
        repository = mock(ReplayEnvironmentRequestRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new ReplayLabProperties();
        properties.getTargets().put("bss-monolith", new ReplayLabProperties.Target());
        service = new ReplayEnvironmentRequestService(
                planService,
                repository,
                objectMapper,
                properties
        );
        caseId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        savedEntity = new AtomicReference<>();
        when(repository.save(any(ReplayEnvironmentRequestEntity.class)))
                .thenAnswer(invocation -> {
                    ReplayEnvironmentRequestEntity entity =
                            invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(requestId);
                    }
                    savedEntity.set(entity);
                    return entity;
                });
    }

    @Test
    void createRequestStoresPlanSnapshotAndRequiresApprovalWhenReady()
            throws Exception {
        when(planService.plan(eq(caseId), eq(true), eq("WIREMOCK")))
                .thenReturn(plan("READY_FOR_APPROVAL", List.of()));

        CreateReplayEnvironmentRequestResponse response = service.create(
                caseId,
                true,
                "WIREMOCK",
                "alice"
        );

        assertThat(response.request().status()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(response.request().replayNamespace())
                .isEqualTo("project-replay-sandbox");
        assertThat(response.request().proposedHost())
                .isEqualTo("replay-fizzms-10228.example.test");
        assertThat(response.request().dryRunOnly()).isTrue();
        assertThat(response.request().realProvisioningEnabled()).isFalse();
        assertThat(savedEntity.get().getPlanSnapshotJson())
                .contains("\"jiraKey\":\"FIZZMS-10228\"");
        assertThat(savedEntity.get().getPlanSnapshotJson())
                .doesNotContain("super-secret");
        ReplayEnvironmentPlanResponse storedPlan = objectMapper.readValue(
                savedEntity.get().getPlanSnapshotJson(),
                ReplayEnvironmentPlanResponse.class
        );
        assertThat(storedPlan.status()).isEqualTo("READY_FOR_APPROVAL");
    }

    @Test
    void createRequestWithBlockersReturnsVisibleBlockers() {
        when(planService.plan(eq(caseId), eq(true), eq("WIREMOCK")))
                .thenReturn(plan(
                        "PLAN_READY",
                        List.of("Sanitized replay request file is missing")
                ));

        CreateReplayEnvironmentRequestResponse response = service.create(
                caseId,
                true,
                "WIREMOCK",
                "alice"
        );

        assertThat(response.request().status()).isEqualTo("PLAN_READY");
        assertThat(response.request().blockers())
                .contains("Sanitized replay request file is missing");
    }

    @Test
    void approveWithGuardrailsFalseReturnsBadRequest() {
        assertThatThrownBy(() -> service.approve(
                requestId,
                new ApproveReplayEnvironmentRequest("alice", "ok", false)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void approveWithGuardrailsTrueSetsApproved() {
        ReplayEnvironmentRequestEntity entity = entity("APPROVAL_REQUIRED");
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentRequestResponse response = service.approve(
                requestId,
                new ApproveReplayEnvironmentRequest("alice", "approved", true)
        );

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(savedEntity.get().getApprovedBy()).isEqualTo("alice");
        assertThat(savedEntity.get().getApprovedAt()).isNotNull();
        assertThat(response.nextActions()).contains(
                ReplayEnvironmentRequestService.PROVISIONING_DISABLED_NEXT_ACTION
        );
    }

    @Test
    void approveRejectedRequestReturnsConflict() {
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("REJECTED")));

        assertThatThrownBy(() -> service.approve(
                requestId,
                new ApproveReplayEnvironmentRequest("alice", "ok", true)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rejectSetsRejected() {
        ReplayEnvironmentRequestEntity entity = entity("APPROVAL_REQUIRED");
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentRequestResponse response = service.reject(
                requestId,
                new RejectReplayEnvironmentRequest("bob", "needs revision")
        );

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(savedEntity.get().getRejectedBy()).isEqualTo("bob");
        assertThat(savedEntity.get().getRejectedAt()).isNotNull();
        assertThat(savedEntity.get().getRejectionReason())
                .isEqualTo("needs revision");
    }

    @Test
    void provisionReturnsDisabledAndDoesNotCallPlanService() {
        ReplayEnvironmentRequestEntity entity = entity("APPROVED");
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentProvisioningDisabledResponse response =
                service.provision(requestId);

        assertThat(response.message()).isEqualTo(
                ReplayEnvironmentRequestService.PROVISIONING_DISABLED_MESSAGE
        );
        assertThat(response.request().status())
                .isEqualTo("PROVISIONING_DISABLED");
        assertThat(savedEntity.get().isRealProvisioningEnabled()).isFalse();
        assertThat(savedEntity.get().isDryRunOnly()).isTrue();
        verifyNoInteractions(planService);
    }

    @Test
    void storedPlanCanBeReadBack() {
        ReplayEnvironmentRequestEntity entity = entity("APPROVAL_REQUIRED");
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentPlanResponse response = service.getPlan(requestId);

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.jiraKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.toString()).doesNotContain("super-secret");
    }

    @Test
    void unapprovedProvisionReadinessReturnsRequestNotApproved() {
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVAL_REQUIRED")));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.requestApproved()).isFalse();
        assertThat(response.readinessStatus()).isEqualTo("BLOCKED");
        assertThat(response.blockers()).contains("REQUEST_NOT_APPROVED");
        verifyNoInteractions(planService);
    }

    @Test
    void approvedProvisionReadinessWithRealProvisioningDisabledIsBlocked() {
        ReplayEnvironmentRequestEntity entity = entity("APPROVED");
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.requestApproved()).isTrue();
        assertThat(response.realProvisioningEnabled()).isFalse();
        assertThat(response.readinessStatus()).isEqualTo("BLOCKED");
        assertThat(response.blockers())
                .contains("REAL_PROVISIONING_DISABLED");
    }

    @Test
    void readinessDoesNotExposeArgocdTokenValue() {
        properties.getArgocd().setEnabled(true);
        properties.getArgocd().setBaseUrl("https://argocd.example.test");
        properties.getArgocd().setTokenConfigured(true);
        properties.getArgocd().setProject("project-modernization-test2");
        properties.getArgocd().setRealProvisioningEnabled(true);
        ReplayLabProperties.Target target = properties.getTargets()
                .get("bss-monolith");
        target.setSecretStrategy("EXTERNAL_SECRET_KEYS_ONLY");
        target.setDbStrategyConfirmed(true);
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVED")));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.argocdCredentialsConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("secret-token-value");
        assertThat(response.toString()).doesNotContain("Authorization");
    }

    @Test
    void missingSecretStrategyReturnsBlocker() {
        properties.getArgocd().setRealProvisioningEnabled(true);
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVED")));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.secretStrategyConfigured()).isFalse();
        assertThat(response.blockers())
                .contains("SECRET_STRATEGY_NOT_CONFIGURED");
    }

    @Test
    void test2SharedDbWithoutConfirmationReturnsBlocker() {
        properties.getArgocd().setRealProvisioningEnabled(true);
        properties.getTargets().get("bss-monolith")
                .setSecretStrategy("EXTERNAL_SECRET_KEYS_ONLY");
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVED")));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.dbStrategyConfirmed()).isFalse();
        assertThat(response.blockers())
                .contains("DB_STRATEGY_NOT_CONFIRMED");
    }

    @Test
    void proposedHostAndTtlMetadataSetIngressAndCleanupConfigured() {
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVED")));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.ingressConfigured()).isTrue();
        assertThat(response.cleanupConfigured()).isTrue();
    }

    @Test
    void provisionReadinessIsNotReadyWhenRuntimeDependencyBlockersExist() {
        properties.getArgocd().setEnabled(true);
        properties.getArgocd().setBaseUrl("https://argocd.example.test");
        properties.getArgocd().setTokenConfigured(true);
        properties.getArgocd().setProject("project-modernization-test2");
        properties.getArgocd().setRealProvisioningEnabled(true);
        ReplayLabProperties.Target target = properties.getTargets()
                .get("bss-monolith");
        target.setSecretStrategy("EXTERNAL_SECRET_KEYS_ONLY");
        target.setDbStrategyConfirmed(true);
        ReplayEnvironmentRequestEntity entity = entity("APPROVED");
        entity.setPlanSnapshotJson(json(planWithRuntime(
                "READY_FOR_APPROVAL",
                List.of(),
                List.of(runtimeDependency(
                        "activeMq",
                        "ACTIVEMQ",
                        "DISABLED",
                        List.of("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING"),
                        List.of()
                ))
        )));
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));

        ReplayEnvironmentProvisionReadinessResponse response =
                service.provisionReadiness(requestId);

        assertThat(response.readinessStatus()).isEqualTo("NOT_READY");
        assertThat(response.runtimeDependenciesSafe()).isFalse();
        assertThat(response.runtimeDependencyBlockers())
                .contains("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
        assertThat(response.blockers())
                .contains("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
    }

    @Test
    void demoSummaryUsesStoredPlanAndReadinessOnly() {
        when(repository.findById(requestId))
                .thenReturn(Optional.of(entity("APPROVED")));

        ReplayEnvironmentDemoSummaryResponse response =
                service.demoSummary(requestId);

        assertThat(response.jiraKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.targetKey()).isEqualTo("bss-monolith");
        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.requestStatus()).isEqualTo("APPROVED");
        assertThat(response.planStatus()).isEqualTo("READY_FOR_APPROVAL");
        assertThat(response.readinessStatus()).isEqualTo("BLOCKED");
        assertThat(response.backendReplayApp()).isEqualTo("bss-backend-replay");
        assertThat(response.customerUiReplayApp()).isNull();
        assertThat(response.replayNamespace())
                .isEqualTo("project-replay-sandbox");
        assertThat(response.proposedHost())
                .isEqualTo("replay-fizzms-10228.example.test");
        assertThat(response.accessMode()).isEqualTo("SINGLE_HOST_PATH_BASED");
        assertThat(response.dbStrategy()).isEqualTo("TEST2_SHARED_DB");
        assertThat(response.sanitizedInputAttached()).isTrue();
        assertThat(response.runtimeDependenciesSafe()).isTrue();
        assertThat(response.runtimeDependencyBlockers()).isEmpty();
        assertThat(response.blockers())
                .contains("REAL_PROVISIONING_DISABLED");
        assertThat(response.guardrails()).contains("dry-run first");
        assertThat(response.nextActions()).contains(
                ReplayEnvironmentRequestService.PROVISIONING_DISABLED_NEXT_ACTION
        );
        assertThat(response.demoNarrative()).contains("FIZZMS-10228");
        assertThat(response.toString()).doesNotContain("BSS_DB_PASSWORD");
        assertThat(response.toString()).doesNotContain("sanitizedRequestBody");
        verifyNoInteractions(planService);
    }

    private ReplayEnvironmentRequestEntity entity(String status) {
        ReplayEnvironmentRequestEntity entity =
                new ReplayEnvironmentRequestEntity();
        entity.setId(requestId);
        entity.setCaseId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(status);
        entity.setReplayNamespace("project-replay-sandbox");
        entity.setProposedHost("replay-fizzms-10228.example.test");
        entity.setDryRunOnly(true);
        entity.setRealProvisioningEnabled(false);
        entity.setPlanSnapshotJson(json(plan("READY_FOR_APPROVAL", List.of())));
        return entity;
    }

    private String json(ReplayEnvironmentPlanResponse plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ReplayEnvironmentPlanResponse plan(
            String status,
            List<String> blockers
    ) {
        return planWithRuntime(status, blockers, List.of());
    }

    private ReplayEnvironmentPlanResponse planWithRuntime(
            String status,
            List<String> blockers,
            List<ReplayRuntimeDependencyPlan> runtimeDependencies
    ) {
        ReplayEnvironmentComponentPlan backend =
                new ReplayEnvironmentComponentPlan(
                        "BACKEND",
                        "bss-backend",
                        "bss-backend-replay",
                        "https://bitbucket.company.com/scm/fdi/helm-charts.git",
                        "bss-backend",
                        "test2",
                        "project-test2-values.yaml",
                        "project-replay-sandbox",
                        "583959041857.dkr.ecr.ca-central-1.amazonaws.com/project-test2-bss-backend",
                        "backend",
                        "/DCE-CommerceBackend/actuator/health",
                        Map.of("image", Map.of("tag", "backend")),
                        List.of("BSS_ALLOWED_ORIGINS"),
                        List.of("BSS_DB_PASSWORD"),
                        List.of()
                );
        return new ReplayEnvironmentPlanResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                status,
                "Plan ready",
                new ReplayArgoCdInventoryContext(
                        "project-modernization-test2",
                        "project-test2-eks",
                        "https://kubernetes.default.svc",
                        List.of("bss-backend"),
                        Map.of("sanitizedReplayInput", Map.of(
                                "traceIdPresent", true,
                                "orderIdPresent", true
                        ))
                ),
                backend,
                null,
                new ReplayEnvironmentNamespacePlan(
                        "PRE_CREATED_NAMESPACE",
                        "project-replay-sandbox",
                        "project-replay-sandbox",
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
                        "http://bss-backend.project-replay-sandbox.svc.cluster.local:8080",
                        "https://replay-fizzms-10228.example.test/DCE-CommerceBackend",
                        true,
                        false,
                        false,
                        List.of("CUSTOMER_UI_BACKEND_BASE_URL"),
                        List.of("BSS_ALLOWED_ORIGINS"),
                        List.of(),
                        Map.of("dryRunOnly", true)
                ),
                new ReplayEnvironmentStateContinuationPlan(
                        blockers.isEmpty(),
                        "TEST2_DB",
                        List.of("orderId", "traceId"),
                        List.of("sanitized replay input attached"),
                        blockers,
                        List.of("Validate sanitized replay input against state source TEST2_DB")
                ),
                List.of(),
                List.of(),
                runtimeDependencies,
                new ReplayEnvironmentDryRunBundle(
                        true,
                        Map.of("kind", "Application"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("ttl", "PT6H", "cleanupRequired", true)
                ),
                new ReplayEnvironmentReadiness(
                        true,
                        blockers.isEmpty(),
                        false,
                        false,
                        blockers
                ),
                List.of("Human approval before ArgoCD provisioning"),
                blockers,
                List.of("no secret value exposure", "dry-run first"),
                List.of("Review dry-run ArgoCD Application manifests"),
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private ReplayRuntimeDependencyPlan runtimeDependency(
            String name,
            String type,
            String mode,
            List<String> blockers,
            List<String> warnings
    ) {
        return new ReplayRuntimeDependencyPlan(
                name,
                type,
                mode,
                "",
                List.of(),
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                blockers.isEmpty(),
                List.of(),
                blockers,
                warnings,
                List.of()
        );
    }
}

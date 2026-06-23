package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.domain.ReplayInputEntity;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.ReplayInputRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayEnvironmentPlanServiceTest {

    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayInputRepository replayInputRepository;
    private ReplayFixProperties properties;
    private ReplayEnvironmentPlanService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        replayInputRepository = mock(ReplayInputRepository.class);
        properties = new ReplayFixProperties();
        properties.getTargets().put("bss-backend", configuredTarget(false));
        service = new ReplayEnvironmentPlanService(
                caseRepository,
                evidenceRepository,
                replayInputRepository,
                properties
        );
        caseId = UUID.randomUUID();
        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(replayCase(caseId, "bss-backend")));
        when(replayInputRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(Optional.empty());
    }

    @Test
    void returns200UsefulPlanForExistingCaseThroughService() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.jiraKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.backend().componentType()).isEqualTo("BACKEND");
        assertThat(response.argoCdInventory().existingApplicationNames())
                .contains("bss-backend");
    }

    @Test
    void missingConfigDoesNotFail() {
        UUID missingCaseId = UUID.randomUUID();
        when(caseRepository.findById(missingCaseId))
                .thenReturn(Optional.of(replayCase(missingCaseId, "missing")));

        ReplayEnvironmentPlanResponse response =
                service.plan(missingCaseId, false, "WIREMOCK");

        assertThat(response.missingEvidence()).isNotEmpty();
        assertThat(response.readiness().blockers()).isNotEmpty();
    }

    @Test
    void guardrailsAreAlwaysIncluded() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.guardrails()).contains(
                "no production write",
                "no secret value exposure",
                "sanitized request only",
                "dry-run first",
                "IaC namespace approval required",
                "isolated replay namespace only",
                "human approval before ArgoCD provisioning",
                "human approval before fix generation",
                "human approval before PR",
                "TTL cleanup required"
        );
    }

    @Test
    void namespaceStrategyIsIacRequiredWithoutPreCreatedNamespace() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.namespacePlan().strategy()).isEqualTo("IAC_REQUIRED");
        assertThat(response.namespacePlan().iacRequired()).isTrue();
        assertThat(response.namespacePlan().namespaceCreationAllowed()).isFalse();
        assertThat(response.readiness().blockers())
                .contains("Replay namespace must be created by IaC before ArgoCD provisioning");
    }

    @Test
    void namespaceStrategyIsPreCreatedWhenConfigured() {
        properties.getTargets().put("bss-backend", configuredTarget(true));

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.namespacePlan().strategy())
                .isEqualTo("PRE_CREATED_NAMESPACE");
        assertThat(response.namespacePlan().existingNamespace())
                .isEqualTo("replay-fizzms-10228");
        assertThat(response.namespacePlan().iacRequired()).isFalse();
    }

    @Test
    void includeCustomerUiTrueIncludesCustomerUiPlanWhenConfigured() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, true, "WIREMOCK");

        assertThat(response.customerUi()).isNotNull();
        assertThat(response.customerUi().componentType())
                .isEqualTo("CUSTOMER_UI");
        assertThat(response.customerUi().existingArgoCdApplicationName())
                .isEqualTo("frontend-customer-ui");
    }

    @Test
    void backendComponentIncludesDeploymentFieldsWhenConfigured() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.backend().chartPath()).isEqualTo("bss-backend");
        assertThat(response.backend().targetRevision()).isEqualTo("test2");
        assertThat(response.backend().valuesFile())
                .isEqualTo("project-test2-values.yaml");
        assertThat(response.backend().namespace())
                .isEqualTo("project-test-bss-backend");
        assertThat(response.backend().imageRepository())
                .isEqualTo("registry.example.com/bss-backend");
        assertThat(response.backend().imageTag()).isEqualTo("incident-10228");
    }

    @Test
    void dryRunBundleIsDryRunOnly() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.dryRunBundle().dryRunOnly()).isTrue();
        assertThat(response.dryRunBundle().backendArgoCdApplicationManifest())
                .containsEntry("kind", "Application");
    }

    @Test
    void secretValuesAreNeverExposed() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.toString()).doesNotContain("super-secret-token");
        assertThat(response.backend().secretKeysRequired())
                .contains("CUSTOMER_API_TOKEN");
        assertThat(response.mockDependencies().get(0).originalValueMasked())
                .isEqualTo("<configured>");
    }

    @Test
    void mockDependenciesAreIncludedFromConfig() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.mockDependencies())
                .extracting("dependencyName")
                .contains("customer-profile");
        assertThat(response.mockDependencies().get(0).dependencyType())
                .isEqualTo("HTTP");
        assertThat(response.mockDependencies().get(0).replayMockUrl())
                .contains("replay-mock-server");
    }

    @Test
    void dbSamplePlansAreIncludedFromConfig() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.dbSamplePlans())
                .extracting("domain")
                .contains("customer");
        assertThat(response.dbSamplePlans().get(0).schema()).isEqualTo("public");
        assertThat(response.dbSamplePlans().get(0).readOnlyRequired()).isTrue();
        assertThat(response.dbSamplePlans().get(0).productionWriteAllowed())
                .isFalse();
    }

    @Test
    void test2SharedDbStrategyAppearsWhenConfigured() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.dbStrategyPlan().strategy())
                .isEqualTo("TEST2_SHARED_DB");
        assertThat(response.dbStrategyPlan().backendCanStart()).isTrue();
        assertThat(response.dbStrategyPlan().stateContinuationSupported())
                .isTrue();
        assertThat(response.dbStrategyPlan().productionWriteAllowed()).isFalse();
        assertThat(response.dbStrategyPlan().test2WriteRisk()).isTrue();
        assertThat(response.dbStrategyPlan().requiredSecretKeys())
                .contains("BSS_DB_URL", "BSS_DB_USER", "BSS_DB_PASSWORD");
    }

    @Test
    void unknownDbStrategyAddsBlocker() {
        properties.getTargets().get("bss-backend").setDbStateMode("");

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.dbStrategyPlan().strategy()).isEqualTo("UNKNOWN");
        assertThat(response.dbStrategyPlan().blockers())
                .contains("DB_STRATEGY_NOT_CONFIGURED");
        assertThat(response.readiness().blockers())
                .contains("DB_STRATEGY_NOT_CONFIGURED");
    }

    @Test
    void stateContinuationRequestedWithoutDbStrategyAddsBlocker() {
        ReplayFixProperties.Target target = properties.getTargets()
                .get("bss-backend");
        target.setDbStateMode("");
        target.setStateContinuationRequested(true);

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.stateContinuationPlan().blockers())
                .contains("STATE_CONTINUATION_DB_STRATEGY_REQUIRED");
        assertThat(response.readiness().blockers())
                .contains("STATE_CONTINUATION_DB_STRATEGY_REQUIRED");
    }

    @Test
    void singleHostPathBasedRoutingUsesDefaultFrontendAndBackendPaths() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, true, "WIREMOCK");

        assertThat(response.accessRoutingPlan().mode())
                .isEqualTo("SINGLE_HOST_PATH_BASED");
        assertThat(response.accessRoutingPlan().frontendPath()).isEqualTo("/");
        assertThat(response.accessRoutingPlan().backendPath())
                .isEqualTo("/DCE-CommerceBackend");
        assertThat(response.accessRoutingPlan().backendInternalServiceUrl())
                .contains(".svc.cluster.local:8080");
        assertThat(response.accessRoutingPlan().corsRisk()).isFalse();
    }

    @Test
    void customerUiBackendConfigMissingAddsBlocker() {
        properties.getTargets().get("bss-backend")
                .setCustomerUiBackendBaseUrlConfigKey("");

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, true, "WIREMOCK");

        assertThat(response.accessRoutingPlan().blockers())
                .contains("CUSTOMER_UI_BACKEND_BASE_URL_CONFIG_KEY_MISSING");
        assertThat(response.readiness().blockers())
                .contains("CUSTOMER_UI_BACKEND_BASE_URL_CONFIG_KEY_MISSING");
    }

    @Test
    void dbTest2SharedCreatesSharedStateWarning() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        ReplayRuntimeDependencyPlan db = runtime(response, "db");

        assertThat(db.mode()).isEqualTo("TEST2_SHARED");
        assertThat(db.warnings()).contains("TEST2_DB_SHARED_STATE_RISK");
        assertThat(db.productionAccessAllowed()).isFalse();
        assertThat(db.productionWriteAllowed()).isFalse();
        assertThat(db.test2AccessAllowed()).isTrue();
        assertThat(db.requiredSecretKeys())
                .contains("BSS_DB_URL", "BSS_DB_USER", "BSS_DB_PASSWORD");
    }

    @Test
    void dbWriteAllowedWithoutConfirmationCreatesBlocker() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(runtime(response, "db").blockers())
                .contains("TEST2_DB_WRITE_NOT_CONFIRMED");
        assertThat(response.readiness().blockers())
                .contains("TEST2_DB_WRITE_NOT_CONFIRMED");
    }

    @Test
    void dbWriteAllowedWithConfirmationHasNoWriteBlocker() {
        properties.getTargets().get("bss-backend")
                .setDbStrategyConfirmed(true);

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(runtime(response, "db").blockers())
                .doesNotContain("TEST2_DB_WRITE_NOT_CONFIRMED");
    }

    @Test
    void activeMqDisabledCreatesListenerOverrideWithoutBlocker() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        ReplayRuntimeDependencyPlan activeMq = runtime(response, "activeMq");

        assertThat(activeMq.mode()).isEqualTo("DISABLED");
        assertThat(activeMq.configOverrides())
                .contains("BSS_MQ_LISTENER_ENABLED=false");
        assertThat(activeMq.blockers())
                .doesNotContain("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
        assertThat(activeMq.warnings())
                .contains("ACTIVEMQ_CONNECTION_MAY_STILL_BE_REQUIRED_FOR_STARTUP");
    }

    @Test
    void activeMqUnknownCreatesBlocker() {
        properties.getTargets().get("bss-backend").setActiveMqMode("");

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(runtime(response, "activeMq").blockers())
                .contains("RUNTIME_DEPENDENCY_MODE_UNKNOWN:activeMq");
    }

    @Test
    void kafkaDisabledWithoutListenerConfigCreatesBlocker() {
        properties.getTargets().get("bss-backend")
                .setKafkaListenerEnabledConfigKey("");

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(runtime(response, "kafka").blockers())
                .contains("KAFKA_DISABLE_CONFIG_KEY_MISSING");
    }

    @Test
    void redisTest2SharedCreatesSharedStateWarning() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        ReplayRuntimeDependencyPlan redis = runtime(response, "redis");

        assertThat(redis.mode()).isEqualTo("TEST2_SHARED");
        assertThat(redis.warnings()).contains("REDIS_SHARED_TEST2_STATE_RISK");
        assertThat(redis.configOverrides())
                .contains("BSS_REDIS_KEY_PREFIX=replay-${caseId}");
    }

    @Test
    void httpMockServerDependenciesAreReplaySafe() {
        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        ReplayRuntimeDependencyPlan http = runtime(response, "customer-profile");

        assertThat(http.dependencyType()).isEqualTo("HTTP");
        assertThat(http.mode()).isEqualTo("MOCK_SERVER");
        assertThat(http.replaySafe()).isTrue();
        assertThat(http.productionAccessAllowed()).isFalse();
    }

    @Test
    void replayInputRemovesMissingReplayRequestAndIdentifierBlockers() {
        ReplayCaseEntity replayCase = replayCase(caseId, "bss-backend");
        replayCase.setOrderId(null);
        replayCase.setTraceId(null);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase));
        properties.getTargets().get("bss-backend").getReplay()
                .setRequestFile("");
        when(replayInputRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(Optional.of(replayInput(caseId)));

        ReplayEnvironmentPlanResponse response =
                service.plan(caseId, false, "WIREMOCK");

        assertThat(response.missingEvidence())
                .doesNotContain("Sanitized replay request file is missing");
        assertThat(response.missingEvidence())
                .doesNotContain(
                        "Sanitized replay identifiers are missing: orderId or traceId"
                );
        assertThat(response.readiness().blockers())
                .doesNotContain("STATE_CONTINUATION_BUSINESS_KEYS_MISSING");
        assertThat(response.stateContinuationPlan().requiredBusinessKeys())
                .contains("traceId", "businessKey");
        assertThat(response.stateContinuationPlan()
                .canContinueFromExistingState()).isTrue();
        assertThat(response.argoCdInventory().rawHints())
                .containsKey("sanitizedReplayInput");
        assertThat(response.toString()).doesNotContain("sanitizedRequestBody");
    }

    private ReplayCaseEntity replayCase(UUID id, String targetKey) {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(id);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey(targetKey);
        entity.setStatus(ReplayCaseStatus.NEW);
        entity.setOrderId("ORD-123");
        entity.setTraceId("trace-abc");
        entity.setIncidentTime(Instant.parse("2026-06-21T10:15:30Z"));
        entity.setSourceBranch("incident/FIZZMS-10228");
        entity.setSourceCommit("4f8d1c2a9b6e7f00112233445566778899aabbcc");
        entity.setImageTag("registry.example.com/bss-backend:incident-10228");
        return entity;
    }

    private ReplayRuntimeDependencyPlan runtime(
            ReplayEnvironmentPlanResponse response,
            String dependencyName
    ) {
        return response.runtimeDependencies().stream()
                .filter(plan -> dependencyName.equals(plan.dependencyName()))
                .findFirst()
                .orElseThrow();
    }

    private ReplayInputEntity replayInput(UUID caseId) {
        ReplayInputEntity entity = new ReplayInputEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-backend");
        entity.setEndpointPath("/DCE-CommerceBackend/orders/replay");
        entity.setHttpMethod("POST");
        entity.setTraceId("trace-replay");
        entity.setBusinessKey("order:ORD-123");
        entity.setSource("MANUAL");
        entity.setSanitized(true);
        entity.setContainsSecrets(false);
        entity.setContainsPersonalData(false);
        return entity;
    }

    private ReplayFixProperties.Target configuredTarget(
            boolean preCreatedNamespace
    ) {
        ReplayFixProperties.Target target = new ReplayFixProperties.Target();
        target.setApplicationKey("bss");
        target.setArgocdProject("project-test");
        target.setClusterName("EKS test2");
        target.setDestinationServer("https://kubernetes.default.svc");
        target.setBackendArgoCdApplicationName("bss-backend");
        target.setBackendSourceRepoUrl(
                "https://bitbucket.example.com/scm/platform/helm-charts.git"
        );
        target.setBackendChartPath("bss-backend");
        target.setBackendTargetRevision("test2");
        target.setBackendValuesFile("project-test2-values.yaml");
        target.setBackendNamespace("project-test-bss-backend");
        target.setBackendImageRepository("registry.example.com/bss-backend");
        target.setBackendImageTag("incident-10228");
        target.setBackendHealthEndpoint("/actuator/health");
        target.setCustomerUiArgoCdApplicationName("frontend-customer-ui");
        target.setCustomerUiSourceRepoUrl(
                "https://bitbucket.example.com/scm/platform/helm-charts.git"
        );
        target.setCustomerUiChartPath("frontend/customer-ui");
        target.setCustomerUiTargetRevision("HEAD");
        target.setCustomerUiValuesFile("project-test2-values.yaml");
        target.setCustomerUiNamespace("project-test-frontend");
        target.setCustomerUiImageRepository("registry.example.com/customer-ui");
        target.setCustomerUiImageTag("HEAD");
        target.setCustomerUiHealthEndpoint("/health");
        target.setReplayNamespacePrefix("replay");
        target.setDbStateMode("TEST2_SHARED_DB");
        target.setStateContinuationRequested(true);
        target.setDbRuntimeMode("TEST2_SHARED");
        target.setDbRuntimeUserMode("EXISTING_TEST2_APP_USER");
        target.setDbTest2WriteAllowed(true);
        target.setDbTest2WriteRequiresApproval(true);
        target.setBackendServicePort(8080);
        target.setBackendContextPath("/DCE-CommerceBackend");
        target.setAccessMode("SINGLE_HOST_PATH_BASED");
        target.setReplayHostSuffix("example.test");
        target.setCustomerUiBackendBaseUrlConfigKey(
                "CUSTOMER_UI_BACKEND_BASE_URL"
        );
        target.setBackendAllowedOriginsConfigKey("BSS_ALLOWED_ORIGINS");
        target.setRequiredDbSecretKeys(List.of(
                "BSS_DB_URL",
                "BSS_DB_USER",
                "BSS_DB_PASSWORD"
        ));
        target.setRequiredMqSecretKeys(List.of(
                "BSS_ACTIVEMQ_URL",
                "BSS_ACTIVEMQ_USER",
                "BSS_ACTIVEMQ_PASSWORD"
        ));
        target.setRequiredKafkaSecretKeys(List.of("BSS_KAFKA_URL"));
        target.setRequiredRedisSecretKeys(List.of(
                "BSS_REDIS_URL",
                "BSS_REDIS_PORT"
        ));
        target.setActiveMqMode("DISABLED");
        target.setActiveMqConnectionRequired(true);
        target.setActiveMqConsumerEnabled(false);
        target.setActiveMqProducerEnabled(false);
        target.setKafkaMode("DISABLED");
        target.setKafkaConnectionRequired(false);
        target.setKafkaConsumerEnabled(false);
        target.setKafkaProducerEnabled(false);
        target.setRedisMode("TEST2_SHARED");
        target.setRedisKeyPrefixRequired(true);
        target.setEmailMode("MOCK_SERVER");
        target.setHttpExternalMode("MOCK_SERVER");
        target.setMqListenerEnabledConfigKey("BSS_MQ_LISTENER_ENABLED");
        target.setKafkaListenerEnabledConfigKey("BSS_KAFKA_LISTENER_ENABLED");
        target.setRedisKeyPrefixConfigKey("BSS_REDIS_KEY_PREFIX");
        target.setRequiredCustomerUiConfigKeys(List.of("CUSTOMER_UI_API_BASE"));
        target.setRequiredBackendConfigKeys(List.of("BSS_ALLOWED_ORIGINS"));
        if (preCreatedNamespace) {
            target.setPreCreatedReplayNamespace("replay-fizzms-10228");
        }
        target.getReplay().setRequestFile("fixtures/sanitized-request.json");
        target.setEnvironment(new LinkedHashMap<>(Map.of(
                "CUSTOMER_API_URL", "https://customer.example.com/api",
                "CUSTOMER_API_TOKEN", "super-secret-token"
        )));

        ReplayFixProperties.ExternalDependency dependency =
                new ReplayFixProperties.ExternalDependency();
        dependency.setDependencyName("customer-profile");
        dependency.setDependencyType("HTTP");
        dependency.setConfigKey("CUSTOMER_API_TOKEN");
        dependency.setMockPath("/customer-profile");
        dependency.setMockType("WIREMOCK");
        dependency.setResponseSource("DB_SAMPLE");
        target.setExternalDependencies(new LinkedHashMap<>());
        target.getExternalDependencies().put("customer-profile", dependency);

        ReplayFixProperties.DbSampleDomain domain =
                new ReplayFixProperties.DbSampleDomain();
        domain.setDomain("customer");
        domain.setSchema("public");
        domain.setTableName("customer_profile");
        domain.setKeyFields(List.of("customer_id"));
        domain.setSampleQueryTemplate(
                "SELECT status,segment FROM public.customer_profile "
                        + "WHERE customer_id = :customerId"
        );
        domain.setExpectedMockResponseFields(List.of("status", "segment"));
        domain.setSanitizationRules(List.of("mask_name", "mask_email"));
        target.setDbSampleDomains(new LinkedHashMap<>());
        target.getDbSampleDomains().put("customer", domain);
        return target;
    }
}

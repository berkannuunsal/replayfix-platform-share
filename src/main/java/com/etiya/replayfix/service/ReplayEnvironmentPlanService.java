package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayArgoCdInventoryContext;
import com.etiya.replayfix.api.dto.ReplayEnvironmentAccessRoutingPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentComponentPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentDbSamplePlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentDbStrategyPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentDryRunBundle;
import com.etiya.replayfix.api.dto.ReplayEnvironmentMockDependencyPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentNamespacePlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentReadiness;
import com.etiya.replayfix.api.dto.ReplayEnvironmentStateContinuationPlan;
import com.etiya.replayfix.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayInputEntity;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.ReplayInputRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReplayEnvironmentPlanService {

    private static final Logger log = LoggerFactory.getLogger(
            ReplayEnvironmentPlanService.class
    );

    private final ReplayCaseRepository caseRepository;
    @SuppressWarnings("unused")
    private final EvidenceRepository evidenceRepository;
    private final ReplayInputRepository replayInputRepository;
    private final ReplayFixProperties properties;

    public ReplayEnvironmentPlanService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayInputRepository replayInputRepository,
            ReplayFixProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.replayInputRepository = replayInputRepository;
        this.properties = properties;
    }

    public ReplayEnvironmentPlanResponse plan(
            UUID caseId,
            boolean includeCustomerUi,
            String mockMode
    ) {
        log.info("REPLAY_ENV_PLAN_START caseId={}", caseId);
        try {
            ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Replay case not found: " + caseId
                    ));
            List<String> missingEvidence = new ArrayList<>();
            ReplayFixProperties.Target target = properties.getTargets()
                    .get(replayCase.getTargetKey());
            if (target == null) {
                missingEvidence.add("Target configuration is missing for "
                        + replayCase.getTargetKey());
                target = new ReplayFixProperties.Target();
            }
            Optional<ReplayInputEntity> latestReplayInput =
                    replayInputRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                            caseId
                    );
            if (latestReplayInput == null) {
                latestReplayInput = Optional.empty();
            }

            ReplayArgoCdInventoryContext inventory =
                    argoCdInventory(target, includeCustomerUi);
            latestReplayInput.ifPresent(input -> inventory.rawHints().put(
                    "sanitizedReplayInput",
                    safeReplayInputSummary(input)
            ));
            log.info(
                    "REPLAY_ENV_TARGET_CONTEXT_BUILT caseId={} targetKey={}",
                    caseId,
                    replayCase.getTargetKey()
            );

            ReplayEnvironmentNamespacePlan namespacePlan =
                    namespacePlan(replayCase, target);
            ReplayEnvironmentComponentPlan backend = componentPlan(
                    replayCase,
                    target,
                    namespacePlan,
                    "BACKEND",
                    missingEvidence
            );
            ReplayEnvironmentComponentPlan customerUi = includeCustomerUi
                    ? componentPlan(
                            replayCase,
                            target,
                            namespacePlan,
                            "CUSTOMER_UI",
                            missingEvidence
                    )
                    : null;

            List<ReplayEnvironmentMockDependencyPlan> mockDependencies =
                    mockDependencies(target, namespacePlan, mockMode, missingEvidence);
            log.info(
                    "REPLAY_ENV_MOCK_PLAN_BUILT caseId={} dependencyCount={}",
                    caseId,
                    mockDependencies.size()
            );

            List<ReplayEnvironmentDbSamplePlan> dbSamplePlans =
                    dbSamplePlans(target, missingEvidence);
            log.info(
                    "REPLAY_ENV_DB_SAMPLE_PLAN_BUILT caseId={} planCount={}",
                    caseId,
                    dbSamplePlans.size()
            );

            addReplayInputEvidence(
                    replayCase,
                    target,
                    latestReplayInput,
                    missingEvidence
            );
            ReplayEnvironmentDbStrategyPlan dbStrategyPlan =
                    dbStrategyPlan(target, dbSamplePlans);
            ReplayEnvironmentAccessRoutingPlan accessRoutingPlan =
                    accessRoutingPlan(replayCase, target, namespacePlan, backend);
            ReplayEnvironmentStateContinuationPlan stateContinuationPlan =
                    stateContinuationPlan(
                            replayCase,
                            target,
                            dbStrategyPlan,
                            latestReplayInput
                    );
            List<ReplayRuntimeDependencyPlan> runtimeDependencies =
                    runtimeDependencies(target, mockDependencies);
            List<String> blockers = blockers(
                    missingEvidence,
                    namespacePlan,
                    backend,
                    customerUi,
                    dbStrategyPlan,
                    accessRoutingPlan,
                    stateContinuationPlan,
                    runtimeDependencies
            );
            ReplayEnvironmentReadiness readiness =
                    new ReplayEnvironmentReadiness(
                            true,
                            blockers.isEmpty(),
                            false,
                            false,
                            blockers
                    );
            ReplayEnvironmentDryRunBundle dryRunBundle = dryRunBundle(
                    replayCase,
                    target,
                    inventory,
                    backend,
                    customerUi,
                    namespacePlan,
                    mockDependencies
            );
            log.info(
                    "REPLAY_ENV_MANIFEST_BUNDLE_DRY_RUN_BUILT caseId={}",
                    caseId
            );

            ReplayEnvironmentPlanResponse response =
                    new ReplayEnvironmentPlanResponse(
                            replayCase.getId(),
                            replayCase.getJiraKey(),
                            replayCase.getTargetKey(),
                            status(readiness, missingEvidence, namespacePlan),
                            summary(replayCase, readiness, missingEvidence),
                            inventory,
                            backend,
                            customerUi,
                            namespacePlan,
                            dbStrategyPlan,
                            accessRoutingPlan,
                            stateContinuationPlan,
                            mockDependencies,
                            dbSamplePlans,
                            runtimeDependencies,
                            dryRunBundle,
                            readiness,
                            requiredApprovals(namespacePlan),
                            List.copyOf(new LinkedHashSet<>(missingEvidence)),
                            guardrails(),
                            nextActions(
                                    readiness,
                                    namespacePlan,
                                    dbStrategyPlan,
                                    accessRoutingPlan,
                                    stateContinuationPlan,
                                    runtimeDependencies
                            ),
                            Instant.now()
                    );
            log.info(
                    missingEvidence.isEmpty()
                            ? "REPLAY_ENV_PLAN_READY caseId={}"
                            : "REPLAY_ENV_PLAN_PARTIAL caseId={} missingEvidenceCount={}",
                    caseId,
                    missingEvidence.size()
            );
            return response;
        } catch (RuntimeException exception) {
            log.warn(
                    "REPLAY_ENV_PLAN_FAILED caseId={} exceptionClass={} message={}",
                    caseId,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception
            );
            throw exception;
        }
    }

    private ReplayArgoCdInventoryContext argoCdInventory(
            ReplayFixProperties.Target target,
            boolean includeCustomerUi
    ) {
        List<String> applications = new ArrayList<>();
        if (!isBlank(target.getBackendArgoCdApplicationName())) {
            applications.add(target.getBackendArgoCdApplicationName());
        } else if (!isBlank(target.getArgocdApplicationName())) {
            applications.add(target.getArgocdApplicationName());
        }
        if (includeCustomerUi
                && !isBlank(target.getCustomerUiArgoCdApplicationName())) {
            applications.add(target.getCustomerUiArgoCdApplicationName());
        }
        return new ReplayArgoCdInventoryContext(
                firstNonBlank(target.getArgocdProject(), "default"),
                firstNonBlank(target.getClusterName(), "EKS test2"),
                firstNonBlank(
                        target.getDestinationServer(),
                        target.getArgocdDestinationCluster(),
                        "https://kubernetes.default.svc"
                ),
                List.copyOf(applications),
                rawHints(target)
        );
    }

    private ReplayEnvironmentNamespacePlan namespacePlan(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target
    ) {
        String proposed = sanitizeKubernetesName(firstNonBlank(
                target.getReplayNamespacePrefix(),
                properties.getNamespacePrefix(),
                "replayfix"
        ) + "-" + replayCase.getTargetKey() + "-" + replayCase.getJiraKey());
        if (!isBlank(target.getPreCreatedReplayNamespace())) {
            return new ReplayEnvironmentNamespacePlan(
                    "PRE_CREATED_NAMESPACE",
                    target.getPreCreatedReplayNamespace(),
                    target.getPreCreatedReplayNamespace(),
                    false,
                    false,
                    List.of()
            );
        }
        return new ReplayEnvironmentNamespacePlan(
                firstNonBlank(target.getNamespaceStrategy(), "IAC_REQUIRED"),
                firstNonBlank(target.getBackendNamespace(), replayCase.getNamespace()),
                proposed,
                false,
                true,
                List.of("Replay namespace must be created by IaC before ArgoCD provisioning")
        );
    }

    private ReplayEnvironmentComponentPlan componentPlan(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target,
            ReplayEnvironmentNamespacePlan namespacePlan,
            String componentType,
            List<String> missingEvidence
    ) {
        boolean backend = "BACKEND".equals(componentType);
        String existingApp = backend
                ? firstNonBlank(
                        target.getBackendArgoCdApplicationName(),
                        target.getArgocdApplicationName()
                )
                : target.getCustomerUiArgoCdApplicationName();
        String sourceRepo = backend
                ? firstNonBlank(target.getBackendSourceRepoUrl(), target.getCloneUrl())
                : target.getCustomerUiSourceRepoUrl();
        String chartPath = backend
                ? firstNonBlank(target.getBackendChartPath(), target.getHelmChartPath())
                : firstNonBlank(
                        target.getCustomerUiChartPath(),
                        target.getCustomerUiHelmChartPath()
                );
        String targetRevision = backend
                ? firstNonBlank(
                        target.getBackendTargetRevision(),
                        replayCase.getSourceBranch(),
                        target.getDefaultBranch(),
                        target.getGit().getSourceBranch()
                )
                : firstNonBlank(target.getCustomerUiTargetRevision(), "HEAD");
        String valuesFile = backend
                ? target.getBackendValuesFile()
                : target.getCustomerUiValuesFile();
        String namespace = componentNamespace(target, namespacePlan, backend);
        String imageRepository = backend
                ? firstNonBlank(
                        target.getBackendImageRepository(),
                        imageRepositoryFrom(firstNonBlank(
                                replayCase.getImageTag(),
                                target.getBackendImageTag(),
                                target.getImage()
                        ))
                )
                : target.getCustomerUiImageRepository();
        String imageTag = backend
                ? firstNonBlank(
                        target.getBackendImageTag(),
                        imageTagFrom(firstNonBlank(
                                replayCase.getImageTag(),
                                target.getImage()
                        )),
                        replayCase.getImageTag()
                )
                : target.getCustomerUiImageTag();
        String healthEndpoint = backend
                ? firstNonBlank(
                        target.getBackendHealthEndpoint(),
                        target.getHealthPath()
                )
                : target.getCustomerUiHealthEndpoint();

        List<String> missing = missingFields(
                Map.of(
                        "existingArgoCdApplicationName", existingApp,
                        "sourceRepoUrl", sourceRepo,
                        "chartPath", chartPath,
                        "targetRevision", targetRevision,
                        "valuesFile", valuesFile,
                        "namespace", namespace,
                        "imageRepository", imageRepository,
                        "imageTag", imageTag
                )
        );
        if (!missing.isEmpty()) {
            missingEvidence.add(componentType + " component missing "
                    + String.join(", ", missing));
        }
        return new ReplayEnvironmentComponentPlan(
                componentType,
                existingApp,
                replayApplicationName(replayCase, backend),
                sourceRepo,
                chartPath,
                targetRevision,
                valuesFile,
                namespace,
                imageRepository,
                imageTag,
                healthEndpoint,
                helmOverrides(componentType, imageRepository, imageTag, namespace),
                configKeysRequired(target),
                secretKeysRequired(target),
                List.copyOf(missing)
        );
    }

    private String replayApplicationName(ReplayCaseEntity replayCase, boolean backend) {
        return sanitizeKubernetesName(
                "replay-" + replayCase.getJiraKey()
                        + "-"
                        + (backend ? "backend" : "customer-ui")
        );
    }

    private String componentNamespace(
            ReplayFixProperties.Target target,
            ReplayEnvironmentNamespacePlan namespacePlan,
            boolean backend
    ) {
        if ("PRE_CREATED_NAMESPACE".equals(namespacePlan.strategy())) {
            return namespacePlan.existingNamespace();
        }
        return backend
                ? target.getBackendNamespace()
                : target.getCustomerUiNamespace();
    }

    private List<ReplayEnvironmentMockDependencyPlan> mockDependencies(
            ReplayFixProperties.Target target,
            ReplayEnvironmentNamespacePlan namespacePlan,
            String mockMode,
            List<String> missingEvidence
    ) {
        List<ReplayEnvironmentMockDependencyPlan> plans = new ArrayList<>();
        target.getExternalDependencies().forEach((key, dependency) -> {
            String name = firstNonBlank(dependency.getDependencyName(), key);
            List<String> missing = missingFields(Map.of(
                    "dependencyName", name,
                    "dependencyType", dependency.getDependencyType(),
                    "configKey", dependency.getConfigKey(),
                    "responseSource", dependency.getResponseSource()
            ));
            plans.add(new ReplayEnvironmentMockDependencyPlan(
                    name,
                    firstNonBlank(dependency.getDependencyType(), "UNKNOWN"),
                    dependency.getConfigKey(),
                    maskedConfiguredValue(target, dependency.getConfigKey()),
                    mockUrl(namespacePlan, name, dependency.getMockPath()),
                    firstNonBlank(dependency.getMockType(), mockMode, "WIREMOCK"),
                    firstNonBlank(dependency.getResponseSource(), "UNKNOWN"),
                    List.of("sanitized request identifiers", "expected response fields"),
                    missing,
                    Map.of(
                            "status", 200,
                            "body", Map.of("source", "dry-run-template")
                    )
            ));
        });
        if (plans.isEmpty()) {
            inferredDependencyPlans(target, namespacePlan, mockMode)
                    .forEach(plans::add);
        }
        if (plans.isEmpty()) {
            missingEvidence.add(
                    "External dependencies are not configured and no config/secret key hints were found"
            );
        }
        return List.copyOf(plans);
    }

    private List<ReplayEnvironmentMockDependencyPlan> inferredDependencyPlans(
            ReplayFixProperties.Target target,
            ReplayEnvironmentNamespacePlan namespacePlan,
            String mockMode
    ) {
        List<ReplayEnvironmentMockDependencyPlan> plans = new ArrayList<>();
        for (String key : target.getEnvironment().keySet()) {
            if (isDependencyHint(key)) {
                String name = sanitizeKubernetesName(key);
                plans.add(new ReplayEnvironmentMockDependencyPlan(
                        name,
                        dependencyTypeFromKey(key),
                        key,
                        maskedConfiguredValue(target, key),
                        mockUrl(namespacePlan, name, ""),
                        firstNonBlank(mockMode, "WIREMOCK"),
                        "UNKNOWN",
                        List.of("sanitized request identifiers"),
                        List.of("responseSource"),
                        Map.of("status", 200)
                ));
            }
        }
        return plans;
    }

    private List<ReplayEnvironmentDbSamplePlan> dbSamplePlans(
            ReplayFixProperties.Target target,
            List<String> missingEvidence
    ) {
        List<ReplayEnvironmentDbSamplePlan> plans = new ArrayList<>();
        target.getDbSampleDomains().forEach((key, domain) -> {
            String domainName = firstNonBlank(domain.getDomain(), key);
            List<String> missing = missingFields(Map.of(
                    "domain", domainName,
                    "tableName", domain.getTableName(),
                    "keyFields", domain.getKeyFields().isEmpty() ? "" : "configured",
                    "sampleQueryTemplate", domain.getSampleQueryTemplate(),
                    "expectedMockResponseFields",
                    domain.getExpectedMockResponseFields().isEmpty()
                            ? ""
                            : "configured"
            ));
            plans.add(new ReplayEnvironmentDbSamplePlan(
                    domainName,
                    domain.getSchema(),
                    domain.getTableName(),
                    List.copyOf(domain.getKeyFields()),
                    domain.getSampleQueryTemplate(),
                    true,
                    false,
                    List.copyOf(domain.getSanitizationRules()),
                    List.copyOf(domain.getExpectedMockResponseFields()),
                    missing
            ));
        });
        if (plans.isEmpty()) {
            missingEvidence.add("DB sample domains are not configured");
        }
        return List.copyOf(plans);
    }

    private List<ReplayRuntimeDependencyPlan> runtimeDependencies(
            ReplayFixProperties.Target target,
            List<ReplayEnvironmentMockDependencyPlan> mockDependencies
    ) {
        List<ReplayRuntimeDependencyPlan> plans = new ArrayList<>();
        plans.add(dbRuntimeDependency(target));
        plans.add(activeMqRuntimeDependency(target));
        plans.add(kafkaRuntimeDependency(target));
        plans.add(redisRuntimeDependency(target));
        plans.add(emailRuntimeDependency(target));
        plans.addAll(httpRuntimeDependencies(target, mockDependencies));
        return List.copyOf(plans);
    }

    private ReplayRuntimeDependencyPlan dbRuntimeDependency(
            ReplayFixProperties.Target target
    ) {
        String mode = firstNonBlank(target.getDbRuntimeMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();
        boolean test2AccessAllowed = "TEST2_SHARED".equals(mode)
                || "READ_ONLY".equals(mode);
        boolean test2WriteAllowed = "TEST2_SHARED".equals(mode)
                && target.isDbTest2WriteAllowed();
        boolean test2WriteRequiresApproval = "TEST2_SHARED".equals(mode)
                && (target.isDbTest2WriteRequiresApproval()
                || !target.isDbStrategyConfirmed());
        boolean replaySafe = true;
        if ("TEST2_SHARED".equals(mode)) {
            warnings.add("TEST2_DB_SHARED_STATE_RISK");
            if (test2WriteAllowed && !target.isDbStrategyConfirmed()) {
                blockers.add("TEST2_DB_WRITE_NOT_CONFIRMED");
            }
            if (test2WriteAllowed) {
                nextActions.add("Approve test2 DB write risk for replay runtime");
            }
        } else if ("READ_ONLY".equals(mode)) {
            warnings.add("BACKEND_MAY_NOT_START_WITH_READ_ONLY_DB");
        } else if ("UNKNOWN".equals(mode)) {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:db");
            replaySafe = false;
        } else {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:db");
            replaySafe = false;
        }
        return new ReplayRuntimeDependencyPlan(
                "db",
                "DB",
                mode,
                "",
                List.of(),
                List.copyOf(defaultedSecretKeys(
                        target.getRequiredDbSecretKeys(),
                        List.of("BSS_DB_URL", "BSS_DB_USER", "BSS_DB_PASSWORD")
                )),
                false,
                false,
                test2AccessAllowed,
                test2WriteAllowed,
                test2WriteRequiresApproval,
                true,
                false,
                false,
                true,
                replaySafe && blockers.isEmpty(),
                List.of(),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(new LinkedHashSet<>(warnings)),
                List.copyOf(new LinkedHashSet<>(nextActions))
        );
    }

    private ReplayRuntimeDependencyPlan activeMqRuntimeDependency(
            ReplayFixProperties.Target target
    ) {
        String mode = firstNonBlank(target.getActiveMqMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> overrides = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();
        if ("DISABLED".equals(mode)) {
            if (isBlank(target.getMqListenerEnabledConfigKey())) {
                blockers.add("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
            } else {
                overrides.add(target.getMqListenerEnabledConfigKey() + "=false");
            }
            if (target.isActiveMqConnectionRequired()) {
                warnings.add("ACTIVEMQ_CONNECTION_MAY_STILL_BE_REQUIRED_FOR_STARTUP");
            }
        } else if ("UNKNOWN".equals(mode)) {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:activeMq");
        }
        if (target.isActiveMqConsumerEnabled()) {
            blockers.add("ACTIVEMQ_CONSUMER_NOT_DISABLED");
        }
        if (target.isActiveMqProducerEnabled()) {
            warnings.add("ACTIVEMQ_PRODUCER_ENABLED_REVIEW_REQUIRED");
        }
        if (!blockers.isEmpty()) {
            nextActions.add("Disable or isolate ActiveMQ listeners before provisioning");
        }
        return new ReplayRuntimeDependencyPlan(
                "activeMq",
                "ACTIVEMQ",
                mode,
                firstNonBlank(target.getMqListenerEnabledConfigKey(), "BSS_MQ_LISTENER_ENABLED"),
                List.of(firstNonBlank(target.getMqListenerEnabledConfigKey(), "BSS_MQ_LISTENER_ENABLED")),
                List.copyOf(target.getRequiredMqSecretKeys()),
                false,
                false,
                !"UNKNOWN".equals(mode),
                false,
                false,
                target.isActiveMqConnectionRequired(),
                target.isActiveMqConsumerEnabled(),
                target.isActiveMqProducerEnabled(),
                target.isActiveMqConnectionRequired(),
                "DISABLED".equals(mode) && blockers.isEmpty(),
                List.copyOf(new LinkedHashSet<>(overrides)),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(new LinkedHashSet<>(warnings)),
                List.copyOf(new LinkedHashSet<>(nextActions))
        );
    }

    private ReplayRuntimeDependencyPlan kafkaRuntimeDependency(
            ReplayFixProperties.Target target
    ) {
        String mode = firstNonBlank(target.getKafkaMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> overrides = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();
        if ("DISABLED".equals(mode)) {
            if (isBlank(target.getKafkaListenerEnabledConfigKey())) {
                blockers.add("KAFKA_DISABLE_CONFIG_KEY_MISSING");
            } else {
                overrides.add(target.getKafkaListenerEnabledConfigKey() + "=false");
            }
        } else if ("UNKNOWN".equals(mode)) {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:kafka");
        }
        if (target.isKafkaConsumerEnabled()) {
            blockers.add("KAFKA_CONSUMER_NOT_DISABLED");
        }
        if (target.isKafkaProducerEnabled()) {
            warnings.add("KAFKA_PRODUCER_ENABLED_REVIEW_REQUIRED");
        }
        if (!blockers.isEmpty()) {
            nextActions.add("Disable or isolate Kafka listeners before provisioning");
        }
        return new ReplayRuntimeDependencyPlan(
                "kafka",
                "KAFKA",
                mode,
                firstNonBlank(target.getKafkaListenerEnabledConfigKey(), "BSS_KAFKA_LISTENER_ENABLED"),
                List.of(firstNonBlank(target.getKafkaListenerEnabledConfigKey(), "BSS_KAFKA_LISTENER_ENABLED")),
                List.copyOf(target.getRequiredKafkaSecretKeys()),
                false,
                false,
                !"UNKNOWN".equals(mode),
                false,
                false,
                target.isKafkaConnectionRequired(),
                target.isKafkaConsumerEnabled(),
                target.isKafkaProducerEnabled(),
                target.isKafkaConnectionRequired(),
                "DISABLED".equals(mode) && blockers.isEmpty(),
                List.copyOf(new LinkedHashSet<>(overrides)),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(new LinkedHashSet<>(warnings)),
                List.copyOf(new LinkedHashSet<>(nextActions))
        );
    }

    private ReplayRuntimeDependencyPlan redisRuntimeDependency(
            ReplayFixProperties.Target target
    ) {
        String mode = firstNonBlank(target.getRedisMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();
        List<String> overrides = new ArrayList<>();
        if ("TEST2_SHARED".equals(mode)) {
            warnings.add("REDIS_SHARED_TEST2_STATE_RISK");
            if (!isBlank(target.getRedisKeyPrefixConfigKey())) {
                overrides.add(target.getRedisKeyPrefixConfigKey()
                        + "=replay-${caseId}");
                nextActions.add("Use replay Redis key prefix for shared test2 Redis");
            } else if (target.isRedisKeyPrefixRequired()) {
                nextActions.add("Confirm Redis shared usage or configure replay key prefix");
            }
        } else if ("UNKNOWN".equals(mode)) {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:redis");
        }
        return new ReplayRuntimeDependencyPlan(
                "redis",
                "REDIS",
                mode,
                target.getRedisKeyPrefixConfigKey(),
                isBlank(target.getRedisKeyPrefixConfigKey())
                        ? List.of()
                        : List.of(target.getRedisKeyPrefixConfigKey()),
                List.copyOf(target.getRequiredRedisSecretKeys()),
                false,
                false,
                "TEST2_SHARED".equals(mode),
                false,
                false,
                "TEST2_SHARED".equals(mode),
                false,
                false,
                "TEST2_SHARED".equals(mode),
                !mode.equals("UNKNOWN") && blockers.isEmpty(),
                List.copyOf(new LinkedHashSet<>(overrides)),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(new LinkedHashSet<>(warnings)),
                List.copyOf(new LinkedHashSet<>(nextActions))
        );
    }

    private ReplayRuntimeDependencyPlan emailRuntimeDependency(
            ReplayFixProperties.Target target
    ) {
        String mode = firstNonBlank(target.getEmailMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        if ("UNKNOWN".equals(mode)) {
            blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:email");
        }
        boolean replaySafe = ("MOCK_SERVER".equals(mode) || "DISABLED".equals(mode))
                && blockers.isEmpty();
        return new ReplayRuntimeDependencyPlan(
                "email",
                "EMAIL",
                mode,
                "BSS_EMAIL_HOST",
                List.of("BSS_EMAIL_HOST"),
                List.of(),
                false,
                false,
                false,
                false,
                false,
                !"DISABLED".equals(mode),
                false,
                "MOCK_SERVER".equals(mode),
                false,
                replaySafe,
                "DISABLED".equals(mode)
                        ? List.of("BSS_EMAIL_ENABLED=false")
                        : List.of(),
                List.copyOf(blockers),
                List.of(),
                replaySafe
                        ? List.of("Confirm no real email send is possible")
                        : List.of("Configure email MOCK_SERVER or DISABLED mode")
        );
    }

    private List<ReplayRuntimeDependencyPlan> httpRuntimeDependencies(
            ReplayFixProperties.Target target,
            List<ReplayEnvironmentMockDependencyPlan> mockDependencies
    ) {
        List<ReplayRuntimeDependencyPlan> plans = new ArrayList<>();
        String mode = firstNonBlank(target.getHttpExternalMode(), "UNKNOWN");
        for (ReplayEnvironmentMockDependencyPlan dependency : mockDependencies) {
            if (!"HTTP".equals(dependency.dependencyType())) {
                continue;
            }
            List<String> blockers = new ArrayList<>();
            if ("UNKNOWN".equals(mode)) {
                blockers.add("RUNTIME_DEPENDENCY_MODE_UNKNOWN:"
                        + dependency.dependencyName());
            }
            boolean hasMockRoute = !isBlank(dependency.replayMockUrl());
            if ("MOCK_SERVER".equals(mode) && !hasMockRoute) {
                blockers.add("HTTP_MOCK_ROUTE_MISSING:"
                        + dependency.dependencyName());
            }
            plans.add(new ReplayRuntimeDependencyPlan(
                    dependency.dependencyName(),
                    "HTTP",
                    mode,
                    dependency.configKey(),
                    isBlank(dependency.configKey())
                            ? List.of()
                            : List.of(dependency.configKey()),
                    List.of(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    "MOCK_SERVER".equals(mode) && blockers.isEmpty(),
                    hasMockRoute
                            ? List.of(dependency.configKey()
                            + "=" + dependency.replayMockUrl())
                            : List.of(),
                    List.copyOf(new LinkedHashSet<>(blockers)),
                    List.of(),
                    blockers.isEmpty()
                            ? List.of("Route HTTP dependency to replay mock server")
                            : List.of("Configure HTTP mock route before provisioning")
            ));
        }
        return List.copyOf(plans);
    }

    private List<String> defaultedSecretKeys(
            List<String> configured,
            List<String> defaults
    ) {
        return configured == null || configured.isEmpty()
                ? defaults
                : configured;
    }

    private ReplayEnvironmentDbStrategyPlan dbStrategyPlan(
            ReplayFixProperties.Target target,
            List<ReplayEnvironmentDbSamplePlan> dbSamplePlans
    ) {
        String strategy = firstNonBlank(target.getDbStateMode(), "UNKNOWN");
        List<String> blockers = new ArrayList<>();
        List<String> requiredInputs = new ArrayList<>();
        if ("UNKNOWN".equals(strategy)) {
            blockers.add("DB_STRATEGY_NOT_CONFIGURED");
        }
        for (ReplayEnvironmentDbSamplePlan plan : dbSamplePlans) {
            requiredInputs.addAll(plan.keyFields());
        }
        boolean test2Shared = "TEST2_SHARED_DB".equals(strategy);
        boolean readOnlySample = "READ_ONLY_SAMPLE".equals(strategy);
        boolean dbClone = "DB_CLONE_REQUIRED".equals(strategy);
        boolean subsetSeed = "SUBSET_SEED_REQUIRED".equals(strategy);
        boolean ephemeral = "EPHEMERAL_DB_REQUIRED".equals(strategy);
        boolean supportedForContinuation = test2Shared || dbClone || subsetSeed;
        if (target.isStateContinuationRequested() && !supportedForContinuation) {
            blockers.add("STATE_CONTINUATION_DB_STRATEGY_REQUIRED");
        }
        if (target.getRequiredDbSecretKeys().isEmpty()) {
            requiredInputs.add("required DB secret key names");
        }
        return new ReplayEnvironmentDbStrategyPlan(
                strategy,
                !"UNKNOWN".equals(strategy) && !ephemeral,
                supportedForContinuation,
                false,
                test2Shared,
                test2Shared || readOnlySample,
                dbClone,
                List.copyOf(target.getRequiredDbSecretKeys()),
                List.copyOf(new LinkedHashSet<>(requiredInputs)),
                List.copyOf(new LinkedHashSet<>(blockers)),
                dbStrategyNextActions(strategy, blockers)
        );
    }

    private List<String> dbStrategyNextActions(
            String strategy,
            List<String> blockers
    ) {
        if (blockers.contains("DB_STRATEGY_NOT_CONFIGURED")) {
            return List.of("Configure dbStateMode for the target");
        }
        if (blockers.contains("STATE_CONTINUATION_DB_STRATEGY_REQUIRED")) {
            return List.of(
                    "Choose TEST2_SHARED_DB, DB_CLONE_REQUIRED, or SUBSET_SEED_REQUIRED for state continuation"
            );
        }
        if ("TEST2_SHARED_DB".equals(strategy)) {
            return List.of(
                    "Confirm read-only DB user for replay validation",
                    "Confirm replay backend cannot write to shared test2 DB"
            );
        }
        return List.of("Review DB state strategy before provisioning");
    }

    private ReplayEnvironmentAccessRoutingPlan accessRoutingPlan(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target,
            ReplayEnvironmentNamespacePlan namespacePlan,
            ReplayEnvironmentComponentPlan backend
    ) {
        String mode = firstNonBlank(target.getAccessMode(), "SINGLE_HOST_PATH_BASED");
        String frontendPath = "/";
        String backendPath = normalizePath(firstNonBlank(
                target.getBackendContextPath(),
                "/DCE-CommerceBackend"
        ));
        String namespace = firstNonBlank(
                namespacePlan.proposedReplayNamespace(),
                namespacePlan.existingNamespace(),
                backend.namespace()
        );
        int port = target.getBackendServicePort() > 0
                ? target.getBackendServicePort()
                : target.getContainerPort();
        String replayBackendServiceName = sanitizeKubernetesName(
                backend.replayArgoCdApplicationName()
        );
        String internalUrl = "http://"
                + replayBackendServiceName
                + "."
                + namespace
                + ".svc.cluster.local:"
                + port;
        String proposedHost = proposedHost(replayCase, target);
        String browserUrl = browserBackendUrl(mode, proposedHost, backendPath);
        List<String> customerUiKeys = new ArrayList<>(
                target.getRequiredCustomerUiConfigKeys()
        );
        if (!isBlank(target.getCustomerUiBackendBaseUrlConfigKey())) {
            customerUiKeys.add(target.getCustomerUiBackendBaseUrlConfigKey());
        }
        List<String> backendKeys = new ArrayList<>(
                target.getRequiredBackendConfigKeys()
        );
        if (!isBlank(target.getBackendAllowedOriginsConfigKey())) {
            backendKeys.add(target.getBackendAllowedOriginsConfigKey());
        }
        List<String> blockers = new ArrayList<>();
        if (isBlank(target.getCustomerUiBackendBaseUrlConfigKey())) {
            blockers.add("CUSTOMER_UI_BACKEND_BASE_URL_CONFIG_KEY_MISSING");
        }
        boolean separateHosts = "SEPARATE_FRONTEND_BACKEND_HOSTS".equals(mode);
        return new ReplayEnvironmentAccessRoutingPlan(
                mode,
                proposedHost,
                frontendPath,
                backendPath,
                internalUrl,
                browserUrl,
                !"INTERNAL_ONLY".equals(mode),
                separateHosts,
                !isBlank(proposedHost) && separateHosts,
                List.copyOf(new LinkedHashSet<>(customerUiKeys)),
                List.copyOf(new LinkedHashSet<>(backendKeys)),
                List.copyOf(blockers),
                Map.of(
                        "mode", mode,
                        "host", proposedHost,
                        "routes", List.of(
                                Map.of(
                                        "path", frontendPath,
                                        "component", "CUSTOMER_UI"
                                ),
                                Map.of(
                                        "path", backendPath,
                                        "component", "BACKEND",
                                        "service", replayBackendServiceName,
                                        "port", port
                                )
                        ),
                        "dryRunOnly", true
                )
        );
    }

    private ReplayEnvironmentStateContinuationPlan stateContinuationPlan(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target,
            ReplayEnvironmentDbStrategyPlan dbStrategyPlan,
            Optional<ReplayInputEntity> latestReplayInput
    ) {
        List<String> businessKeys = new ArrayList<>();
        if (!isBlank(replayCase.getOrderId())) {
            businessKeys.add("orderId");
        }
        if (!isBlank(replayCase.getTraceId())) {
            businessKeys.add("traceId");
        }
        latestReplayInput.ifPresent(input -> {
            if (!isBlank(input.getOrderId())) {
                businessKeys.add("orderId");
            }
            if (!isBlank(input.getTraceId())) {
                businessKeys.add("traceId");
            }
            if (!isBlank(input.getBusinessKey())) {
                businessKeys.add("businessKey");
            }
            if (!isBlank(input.getCustomerId())) {
                businessKeys.add("customerId");
            }
            if (!isBlank(input.getAccountId())) {
                businessKeys.add("accountId");
            }
        });
        List<String> sanitizedInputs = new ArrayList<>();
        if (latestReplayInput.isPresent()) {
            sanitizedInputs.add("sanitized replay input attached");
        } else {
            sanitizedInputs.add("sanitized replay request payload");
        }
        if (businessKeys.isEmpty()) {
            sanitizedInputs.add("sanitized business key");
        }
        List<String> blockers = new ArrayList<>();
        if (target.isStateContinuationRequested()
                && !dbStrategyPlan.stateContinuationSupported()) {
            blockers.add("STATE_CONTINUATION_DB_STRATEGY_REQUIRED");
        }
        if (businessKeys.isEmpty()) {
            blockers.add("STATE_CONTINUATION_BUSINESS_KEYS_MISSING");
        }
        String stateSource = stateSource(dbStrategyPlan.strategy());
        return new ReplayEnvironmentStateContinuationPlan(
                dbStrategyPlan.stateContinuationSupported()
                        && !businessKeys.isEmpty()
                        && blockers.isEmpty(),
                stateSource,
                List.copyOf(new LinkedHashSet<>(businessKeys)),
                List.copyOf(sanitizedInputs),
                List.copyOf(new LinkedHashSet<>(blockers)),
                stateContinuationNextActions(blockers, stateSource)
        );
    }

    private List<String> stateContinuationNextActions(
            List<String> blockers,
            String stateSource
    ) {
        if (blockers.contains("STATE_CONTINUATION_DB_STRATEGY_REQUIRED")) {
            return List.of("Select a DB strategy that supports state continuation");
        }
        if (blockers.contains("STATE_CONTINUATION_BUSINESS_KEYS_MISSING")) {
            return List.of("Provide sanitized orderId, traceId, or equivalent business key");
        }
        return List.of("Validate sanitized replay input against state source " + stateSource);
    }

    private ReplayEnvironmentDryRunBundle dryRunBundle(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target,
            ReplayArgoCdInventoryContext inventory,
            ReplayEnvironmentComponentPlan backend,
            ReplayEnvironmentComponentPlan customerUi,
            ReplayEnvironmentNamespacePlan namespacePlan,
            List<ReplayEnvironmentMockDependencyPlan> mockDependencies
    ) {
        Map<String, Object> mockValues = Map.of(
                "mockMode",
                mockDependencies.isEmpty()
                        ? "WIREMOCK"
                        : mockDependencies.get(0).mockMode(),
                "dependencies",
                mockDependencies.stream()
                        .map(ReplayEnvironmentMockDependencyPlan::dependencyName)
                        .toList(),
                "dryRunOnly",
                true
        );
        return new ReplayEnvironmentDryRunBundle(
                true,
                argoApplicationManifest(inventory, backend),
                customerUi == null ? Map.of() : argoApplicationManifest(
                        inventory,
                        customerUi
                ),
                Map.of(
                        "componentType", "MOCK_SERVER",
                        "chartPath", firstNonBlank(
                                target.getMockServerChartPath(),
                                "mock-server"
                        ),
                        "namespace", namespacePlan.proposedReplayNamespace(),
                        "dryRunOnly", true
                ),
                backend.helmValueOverrides(),
                customerUi == null ? Map.of() : customerUi.helmValueOverrides(),
                mockValues,
                Map.of(
                        "intent", "Route external dependencies to replay mocks only",
                        "denyEgressToProduction", true,
                        "namespaceCreationAllowed", false,
                        "dryRunOnly", true
                ),
                Map.of(
                        "ttl", properties.getCleanupAfter().toString(),
                        "cleanupRequired", true,
                        "caseId", replayCase.getId().toString()
                )
        );
    }

    private Map<String, Object> argoApplicationManifest(
            ReplayArgoCdInventoryContext inventory,
            ReplayEnvironmentComponentPlan component
    ) {
        Map<String, Object> helm = new LinkedHashMap<>();
        if (!isBlank(component.valuesFile())) {
            helm.put("valueFiles", List.of(component.valuesFile()));
        }
        helm.put("valuesObject", component.helmValueOverrides());
        return Map.of(
                "apiVersion", "argoproj.io/v1alpha1",
                "kind", "Application",
                "metadata", Map.of(
                        "name", component.replayArgoCdApplicationName(),
                        "namespace", "argocd",
                        "annotations", Map.of(
                                "replayfix.io/dry-run-only", "true"
                        )
                ),
                "spec", Map.of(
                        "project", inventory.argocdProject(),
                        "source", Map.of(
                                "repoURL", component.sourceRepoUrl(),
                                "path", component.chartPath(),
                                "targetRevision", component.targetRevision(),
                                "helm", helm
                        ),
                        "destination", Map.of(
                                "server", inventory.destinationServer(),
                                "namespace", component.namespace()
                        ),
                        "syncPolicy", Map.of(
                                "automated", false,
                                "dryRunOnly", true
                        )
                )
        );
    }

    private Map<String, Object> helmOverrides(
            String componentType,
            String imageRepository,
            String imageTag,
            String namespace
    ) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("repository", imageRepository);
        image.put("tag", imageTag);
        return Map.of(
                "componentType", componentType,
                "image", image,
                "namespace", namespace,
                "replay", Map.of(
                        "enabled", true,
                        "dryRunOnly", true
                )
        );
    }

    private Map<String, Object> rawHints(ReplayFixProperties.Target target) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("applicationKey", target.getApplicationKey());
        raw.put("argocdProject", target.getArgocdProject());
        raw.put("clusterName", target.getClusterName());
        raw.put("destinationServerConfigured", !isBlank(target.getDestinationServer()));
        raw.put("backendArgoCdApplicationName", target.getBackendArgoCdApplicationName());
        raw.put("customerUiArgoCdApplicationName", target.getCustomerUiArgoCdApplicationName());
        raw.put("backendChartPath", target.getBackendChartPath());
        raw.put("customerUiChartPath", target.getCustomerUiChartPath());
        raw.put("backendValuesFile", target.getBackendValuesFile());
        raw.put("customerUiValuesFile", target.getCustomerUiValuesFile());
        raw.put("preCreatedReplayNamespace", target.getPreCreatedReplayNamespace());
        raw.put("dbStateMode", target.getDbStateMode());
        raw.put("stateContinuationRequested", target.isStateContinuationRequested());
        raw.put("accessMode", target.getAccessMode());
        raw.put(
                "customerUiBackendBaseUrlConfigKey",
                target.getCustomerUiBackendBaseUrlConfigKey()
        );
        raw.put("environmentKeys", target.getEnvironment().keySet());
        raw.put("externalDependencyNames", target.getExternalDependencies().keySet());
        raw.put("dbSampleDomains", target.getDbSampleDomains().keySet());
        return raw;
    }

    private List<String> blockers(
            List<String> missingEvidence,
            ReplayEnvironmentNamespacePlan namespacePlan,
            ReplayEnvironmentComponentPlan backend,
            ReplayEnvironmentComponentPlan customerUi,
            ReplayEnvironmentDbStrategyPlan dbStrategyPlan,
            ReplayEnvironmentAccessRoutingPlan accessRoutingPlan,
            ReplayEnvironmentStateContinuationPlan stateContinuationPlan,
            List<ReplayRuntimeDependencyPlan> runtimeDependencies
    ) {
        List<String> blockers = new ArrayList<>(missingEvidence);
        blockers.addAll(namespacePlan.blockers());
        blockers.addAll(dbStrategyPlan.blockers());
        blockers.addAll(accessRoutingPlan.blockers());
        blockers.addAll(stateContinuationPlan.blockers());
        blockers.addAll(runtimeDependencyBlockers(runtimeDependencies));
        blockers.addAll(backend.missingFields().stream()
                .map(field -> "BACKEND missing " + field)
                .toList());
        if (customerUi != null) {
            blockers.addAll(customerUi.missingFields().stream()
                    .map(field -> "CUSTOMER_UI missing " + field)
                    .toList());
        }
        return List.copyOf(new LinkedHashSet<>(blockers));
    }

    private List<String> runtimeDependencyBlockers(
            List<ReplayRuntimeDependencyPlan> runtimeDependencies
    ) {
        List<String> blockers = new ArrayList<>();
        for (ReplayRuntimeDependencyPlan dependency : runtimeDependencies) {
            blockers.addAll(dependency.blockers());
        }
        return List.copyOf(new LinkedHashSet<>(blockers));
    }

    private List<String> runtimeDependencyWarnings(
            List<ReplayRuntimeDependencyPlan> runtimeDependencies
    ) {
        List<String> warnings = new ArrayList<>();
        for (ReplayRuntimeDependencyPlan dependency : runtimeDependencies) {
            warnings.addAll(dependency.warnings());
        }
        return List.copyOf(new LinkedHashSet<>(warnings));
    }

    private void addReplayInputEvidence(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target,
            Optional<ReplayInputEntity> latestReplayInput,
            List<String> missingEvidence
    ) {
        boolean replayInputAttached = latestReplayInput.isPresent();
        if (!replayInputAttached && isBlank(target.getReplay().getRequestFile())) {
            missingEvidence.add("Sanitized replay request file is missing");
        }
        if (!hasReplayIdentifier(replayCase, latestReplayInput)) {
            missingEvidence.add(
                    "Sanitized replay identifiers are missing: orderId or traceId"
            );
        }
    }

    private boolean hasReplayIdentifier(
            ReplayCaseEntity replayCase,
            Optional<ReplayInputEntity> latestReplayInput
    ) {
        if (!isBlank(replayCase.getOrderId())
                || !isBlank(replayCase.getTraceId())) {
            return true;
        }
        return latestReplayInput
                .map(input -> !isBlank(input.getOrderId())
                        || !isBlank(input.getTraceId())
                        || !isBlank(input.getBusinessKey()))
                .orElse(false);
    }

    private Map<String, Object> safeReplayInputSummary(
            ReplayInputEntity input
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", input.getId());
        summary.put("endpointPath", input.getEndpointPath());
        summary.put("httpMethod", input.getHttpMethod());
        summary.put("source", input.getSource());
        summary.put("sanitized", input.isSanitized());
        summary.put("traceIdPresent", !isBlank(input.getTraceId()));
        summary.put("orderIdPresent", !isBlank(input.getOrderId()));
        summary.put("customerIdPresent", !isBlank(input.getCustomerId()));
        summary.put("accountIdPresent", !isBlank(input.getAccountId()));
        summary.put("businessKeyPresent", !isBlank(input.getBusinessKey()));
        summary.put("createdAt", input.getCreatedAt());
        return summary;
    }

    private List<String> requiredApprovals(
            ReplayEnvironmentNamespacePlan namespacePlan
    ) {
        List<String> approvals = new ArrayList<>();
        if (namespacePlan.iacRequired()) {
            approvals.add("IaC approval for replay namespace creation");
        }
        approvals.add("Human approval before ArgoCD provisioning");
        approvals.add("Human approval before replay execution");
        approvals.add("Human approval before fix generation");
        approvals.add("Human approval before PR");
        return List.copyOf(approvals);
    }

    private List<String> guardrails() {
        return List.of(
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

    private List<String> nextActions(
            ReplayEnvironmentReadiness readiness,
            ReplayEnvironmentNamespacePlan namespacePlan,
            ReplayEnvironmentDbStrategyPlan dbStrategyPlan,
            ReplayEnvironmentAccessRoutingPlan accessRoutingPlan,
            ReplayEnvironmentStateContinuationPlan stateContinuationPlan,
            List<ReplayRuntimeDependencyPlan> runtimeDependencies
    ) {
        List<String> actions = new ArrayList<>();
        actions.addAll(dbStrategyPlan.nextActions());
        actions.addAll(stateContinuationPlan.nextActions());
        runtimeDependencies.forEach(dependency -> {
            actions.addAll(dependency.nextActions());
            dependency.warnings().forEach(warning ->
                    actions.add("Review runtime dependency warning: " + warning)
            );
        });
        if (!accessRoutingPlan.blockers().isEmpty()) {
            actions.add("Configure customer-ui backend base URL key");
        }
        if (readiness.readyForHumanApproval()) {
            actions.add("Review dry-run ArgoCD Application manifests");
            actions.add("Confirm replay namespace is isolated");
            actions.add("Approve ArgoCD provisioning in a separate controlled step");
            return List.copyOf(new LinkedHashSet<>(actions));
        }
        if (namespacePlan.iacRequired()) {
            actions.add("Create or approve replay namespace through IaC");
            actions.add("Resolve missing chart, image, config, and sample evidence");
            actions.add("Regenerate dry-run replay environment plan");
            return List.copyOf(new LinkedHashSet<>(actions));
        }
        actions.add("Resolve missing evidence");
        actions.add("Regenerate dry-run replay environment plan");
        return List.copyOf(new LinkedHashSet<>(actions));
    }

    private String status(
            ReplayEnvironmentReadiness readiness,
            List<String> missingEvidence,
            ReplayEnvironmentNamespacePlan namespacePlan
    ) {
        if (!readiness.blockers().isEmpty() && namespacePlan.iacRequired()) {
            return "BLOCKED";
        }
        if (!missingEvidence.isEmpty()) {
            return "NEEDS_EVIDENCE";
        }
        if (readiness.readyForHumanApproval()) {
            return "READY_FOR_APPROVAL";
        }
        return "PLAN_READY";
    }

    private String summary(
            ReplayCaseEntity replayCase,
            ReplayEnvironmentReadiness readiness,
            List<String> missingEvidence
    ) {
        if (readiness.readyForHumanApproval()) {
            return "Dry-run ArgoCD replay environment plan for Jira "
                    + replayCase.getJiraKey()
                    + " is ready for human approval.";
        }
        return "Dry-run ArgoCD replay environment plan for Jira "
                + replayCase.getJiraKey()
                + " has " + missingEvidence.size()
                + " missing evidence item(s) or approval blocker(s).";
    }

    private List<String> configKeysRequired(ReplayFixProperties.Target target) {
        return target.getEnvironment().keySet().stream()
                .filter(key -> !isSecretKey(key))
                .toList();
    }

    private List<String> secretKeysRequired(ReplayFixProperties.Target target) {
        List<String> keys = target.getEnvironment().keySet().stream()
                .filter(this::isSecretKey)
                .toList();
        return List.copyOf(keys);
    }

    private String maskedConfiguredValue(
            ReplayFixProperties.Target target,
            String key
    ) {
        if (isBlank(key) || !target.getEnvironment().containsKey(key)) {
            return "";
        }
        return "<configured>";
    }

    private String mockUrl(
            ReplayEnvironmentNamespacePlan namespacePlan,
            String dependencyName,
            String mockPath
    ) {
        String namespace = firstNonBlank(
                namespacePlan.proposedReplayNamespace(),
                namespacePlan.existingNamespace(),
                "replayfix"
        );
        return "http://replay-mock-server."
                + namespace
                + ".svc.cluster.local"
                + firstNonBlank(mockPath, "/" + sanitizeKubernetesName(dependencyName));
    }

    private boolean isDependencyHint(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("url")
                || lower.contains("endpoint")
                || lower.contains("client")
                || lower.contains("gateway")
                || lower.contains("kafka")
                || lower.contains("activemq")
                || lower.contains("redis")
                || lower.contains("smtp")
                || lower.contains("mail");
    }

    private String dependencyTypeFromKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("kafka")) return "KAFKA";
        if (lower.contains("activemq")) return "ACTIVEMQ";
        if (lower.contains("redis")) return "REDIS";
        if (lower.contains("smtp") || lower.contains("mail")) return "EMAIL";
        if (lower.contains("url") || lower.contains("endpoint")
                || lower.contains("gateway") || lower.contains("client")) {
            return "HTTP";
        }
        return "UNKNOWN";
    }

    private boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("secret")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("credential")
                || lower.contains("apikey")
                || lower.contains("api_key");
    }

    private String imageRepositoryFrom(String image) {
        if (isBlank(image)) return "";
        int colonIndex = image.lastIndexOf(':');
        int slashIndex = image.lastIndexOf('/');
        if (colonIndex > slashIndex) {
            return image.substring(0, colonIndex);
        }
        return image;
    }

    private String imageTagFrom(String image) {
        if (isBlank(image)) return "";
        int colonIndex = image.lastIndexOf(':');
        int slashIndex = image.lastIndexOf('/');
        if (colonIndex > slashIndex && colonIndex < image.length() - 1) {
            return image.substring(colonIndex + 1);
        }
        return "";
    }

    private String proposedHost(
            ReplayCaseEntity replayCase,
            ReplayFixProperties.Target target
    ) {
        if (isBlank(target.getReplayHostSuffix())) {
            return "";
        }
        String suffix = target.getReplayHostSuffix().startsWith(".")
                ? target.getReplayHostSuffix()
                : "." + target.getReplayHostSuffix();
        return sanitizeKubernetesName("replay-" + replayCase.getJiraKey())
                + suffix;
    }

    private String browserBackendUrl(
            String mode,
            String proposedHost,
            String backendPath
    ) {
        if ("INTERNAL_ONLY".equals(mode)) {
            return "";
        }
        if (isBlank(proposedHost)) {
            return backendPath;
        }
        return "https://" + proposedHost + backendPath;
    }

    private String normalizePath(String path) {
        if (isBlank(path)) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String stateSource(String dbStrategy) {
        return switch (dbStrategy) {
            case "TEST2_SHARED_DB" -> "TEST2_DB";
            case "DB_CLONE_REQUIRED" -> "DB_CLONE";
            case "SUBSET_SEED_REQUIRED" -> "SUBSET_SEED";
            case "READ_ONLY_SAMPLE" -> "SANITIZED_REQUEST_ONLY";
            default -> "UNKNOWN";
        };
    }

    private List<String> missingFields(Map<String, String> fields) {
        List<String> missing = new ArrayList<>();
        fields.forEach((key, value) -> {
            if (isBlank(value)) {
                missing.add(key);
            }
        });
        return List.copyOf(missing);
    }

    private String sanitizeKubernetesName(String value) {
        String sanitized = firstNonBlank(value, "replayfix")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (sanitized.isBlank()) {
            sanitized = "replayfix";
        }
        if (sanitized.length() > 63) {
            sanitized = sanitized.substring(0, 63).replaceAll("-+$", "");
        }
        return sanitized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.RepositoryResolutionResult;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RepositoryResolutionEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryResolutionEvidenceService.class);

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final BitbucketClient bitbucketClient;
    private final RepositoryResolverService resolverService;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public RepositoryResolutionEvidenceService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            BitbucketClient bitbucketClient,
            RepositoryResolverService resolverService,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.bitbucketClient = bitbucketClient;
        this.resolverService = resolverService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvidenceEntity collectRepositoryResolution(UUID caseId, String jiraKey, String targetKey) {
        log.info("REPOSITORY_RESOLUTION_START: caseId={}, jiraKey={}, targetKey={}", caseId, jiraKey, targetKey);

        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        // Validate target exists in configuration
        Map<String, ReplayFixProperties.Target> targets = properties.getTargets();
        if (!targets.containsKey(targetKey)) {
            throw new IllegalArgumentException("Target not configured: " + targetKey);
        }

        ReplayFixProperties.Target targetConfig = targets.get(targetKey);

        try {
            // Get all repositories from Bitbucket
            List<BitbucketRepositoryInfo> repositories = bitbucketClient.listRepositories();
            log.info("BITBUCKET_REPOSITORIES_FETCHED: count={}", repositories.size());

            // Try to find exact match by targetKey configuration
            String configuredRepoSlug = targetConfig.getRepository();
            Optional<BitbucketRepositoryInfo> exactMatch = repositories.stream()
                    .filter(repo -> repo.slug().equalsIgnoreCase(configuredRepoSlug))
                    .findFirst();

            BitbucketRepositoryInfo resolvedRepo;
            double confidence;
            List<String> matchedSignals = new ArrayList<>();
            String resolutionMethod;

            if (exactMatch.isPresent()) {
                // Exact match from target configuration
                resolvedRepo = exactMatch.get();
                confidence = 1.0;
                matchedSignals.add("EXACT_TARGET_CONFIG_MATCH");
                resolutionMethod = "TARGET_CONFIG_EXACT_MATCH";
                log.info("REPOSITORY_RESOLVED_EXACT: targetKey={}, slug={}", targetKey, resolvedRepo.slug());
            } else {
                // Fallback to resolver service (with Jira signals)
                IncidentSignals signals = extractSignals(jiraKey);
                RepositoryResolutionResult resolution = resolverService.resolve(
                        repositories,
                        null, // JiraIssue not needed for now
                        jiraKey,
                        signals,
                        null // IncidentTimeline not needed
                );

                if (resolution.primaryRepositorySlug() == null || resolution.primaryRepositorySlug().isEmpty()) {
                    throw new RuntimeException("Repository resolution failed: " + resolution.warning());
                }

                resolvedRepo = repositories.stream()
                        .filter(repo -> repo.slug().equals(resolution.primaryRepositorySlug()))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Resolved repository not found in list"));

                confidence = resolution.candidates().isEmpty() ? 0.0 :
                        resolution.candidates().get(0).score() / 100.0;
                matchedSignals.addAll(resolution.candidates().isEmpty() ? List.of() :
                        resolution.candidates().get(0).reasons());
                resolutionMethod = "RESOLVER_SERVICE_MATCH";
                log.info("REPOSITORY_RESOLVED_BY_SIGNALS: targetKey={}, slug={}, confidence={}",
                        targetKey, resolvedRepo.slug(), confidence);
            }

            // Build sanitized evidence content (NO CREDENTIALS)
            Map<String, Object> evidenceContent = buildSanitizedEvidence(
                    targetKey,
                    resolvedRepo,
                    targetConfig,
                    confidence,
                    matchedSignals,
                    resolutionMethod
            );

            String contentJson = objectMapper.writeValueAsString(evidenceContent);

            // Persist evidence
            EvidenceEntity evidence = new EvidenceEntity();
            evidence.setCaseId(caseId);
            evidence.setEvidenceType(EvidenceType.REPOSITORY_RESOLUTION);
            evidence.setSource("repository-resolution-service");
            evidence.setContentText(contentJson);
            evidence.setBody(contentJson);
            evidence.setConfidence(confidence);
            evidence.setSanitized(true);

            evidenceRepository.save(evidence);

            // Diagnostic logging (NO CREDENTIALS)
            log.info("REPOSITORY_RESOLUTION_EVIDENCE_SAVED: caseId={}, evidenceId={}, targetKey={}, projectKey={}, repositorySlug={}, sourceBranch={}, confidence={}, primaryResolved={}",
                    caseId, evidence.getId(), targetKey, resolvedRepo.projectKey(), resolvedRepo.slug(), 
                    evidenceContent.get("sourceBranch"), confidence, true);

            return evidence;

        } catch (Exception e) {
            log.error("REPOSITORY_RESOLUTION_FAILED: caseId={}, targetKey={}", caseId, targetKey, e);
            throw new RuntimeException("Repository resolution failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildSanitizedEvidence(
            String targetKey,
            BitbucketRepositoryInfo repo,
            ReplayFixProperties.Target targetConfig,
            double confidence,
            List<String> matchedSignals,
            String resolutionMethod
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();

        // Canonical fields for RepositoryResolutionResult (Jenkins compatibility)
        evidence.put("projectKey", repo.projectKey());
        evidence.put("primaryRepositorySlug", repo.slug());
        evidence.put("candidates", List.of()); // Empty for exact match
        evidence.put("unresolvedSignals", List.of());
        evidence.put("warning", "");

        // Extended fields for detailed evidence
        evidence.put("applicationKey", targetKey);
        evidence.put("targetKey", targetKey);
        evidence.put("repositoryName", repo.name());

        // Backward compatibility fields
        evidence.put("bitbucketProjectKey", repo.projectKey());
        evidence.put("repositorySlug", repo.slug());

        // Sanitized clone URL (remove credentials if any)
        String cloneUrl = sanitizeUrl(repo.cloneUrl());
        evidence.put("cloneUrl", cloneUrl);
        evidence.put("sanitizedCloneUrl", cloneUrl);

        // Source branch from target config
        String sourceBranch = targetConfig.getGit() != null && targetConfig.getGit().getSourceBranch() != null
                ? targetConfig.getGit().getSourceBranch()
                : "test2";
        evidence.put("sourceBranch", sourceBranch);

        evidence.put("confidence", confidence);
        evidence.put("matchedSignals", matchedSignals);
        evidence.put("resolutionMethod", resolutionMethod);
        evidence.put("repositoryState", repo.state());
        evidence.put("primary", true); // Explicit primary flag

        return evidence;
    }

    private String sanitizeUrl(String url) {
        if (url == null) {
            return "";
        }

        // Remove any credentials from URL
        // Example: https://user:pass@bitbucket.com/... -> https://bitbucket.com/...
        return url.replaceAll("://[^@]+@", "://");
    }

    private IncidentSignals extractSignals(String jiraKey) {
        // Simple signal extraction from jiraKey
        List<String> endpoints = new ArrayList<>();
        List<String> httpStatuses = new ArrayList<>();
        List<String> errorCodes = new ArrayList<>();
        List<String> businessTerms = new ArrayList<>();
        List<String> statusValues = new ArrayList<>();
        List<String> serviceHints = new ArrayList<>();

        // Parse jiraKey for signals (e.g., FIZZMS-8346 -> fizz, ms)
        if (jiraKey != null) {
            String[] parts = jiraKey.split("-");
            if (parts.length > 0) {
                String prefix = parts[0].toLowerCase(Locale.ROOT);
                if (prefix.contains("fizz")) {
                    serviceHints.add("fizz");
                    serviceHints.add("fizz-marketplace");
                }
            }
        }

        return new IncidentSignals(
                endpoints,
                httpStatuses,
                errorCodes,
                businessTerms,
                statusValues,
                serviceHints
        );
    }
}

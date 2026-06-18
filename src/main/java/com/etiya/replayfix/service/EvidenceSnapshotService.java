package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.*;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class EvidenceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSnapshotService.class);

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public EvidenceSnapshotService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    public ReplayFixEvidenceSnapshot buildSnapshot(UUID caseId) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        List<EvidenceEntity> allEvidence = evidenceRepository.findByCaseId(caseId);

        // Build evidence ID map
        Map<String, List<UUID>> evidenceIds = allEvidence.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEvidenceType().name(),
                        Collectors.mapping(EvidenceEntity::getId, Collectors.toList())
                ));

        // Extract components
        ReplayFixEvidenceSnapshot.RepositoryInfo repository = buildRepositoryInfo(allEvidence);
        ReplayFixEvidenceSnapshot.JenkinsInfo jenkins = buildJenkinsInfo(allEvidence);
        ReplayFixEvidenceSnapshot.IncidentVersionInfo incidentVersion = buildIncidentVersionInfo(allEvidence);
        ReplayFixEvidenceSnapshot.RuntimeEvidenceInfo runtimeEvidence = buildRuntimeEvidenceInfo(allEvidence);
        ReplayFixEvidenceSnapshot.SourceContextInfo sourceContext = buildSourceContextInfo(allEvidence);
        ReplayFixEvidenceSnapshot.DeterministicRcaInfo deterministicRca = buildDeterministicRcaInfo(allEvidence);
        ReplayFixEvidenceSnapshot.GuardrailsInfo guardrails = buildGuardrailsInfo();

        return new ReplayFixEvidenceSnapshot(
                ReplayFixEvidenceSnapshot.SCHEMA_VERSION,
                caseId,
                caseEntity.getJiraKey(),
                caseEntity.getTargetKey(),
                caseEntity.isSynthetic(),
                repository,
                jenkins,
                incidentVersion,
                runtimeEvidence,
                sourceContext,
                deterministicRca,
                evidenceIds,
                guardrails
        );
    }

    private ReplayFixEvidenceSnapshot.RepositoryInfo buildRepositoryInfo(List<EvidenceEntity> allEvidence) {
        return allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION)
                .findFirst()
                .map(evidence -> {
                    try {
                        RepositoryResolutionResult result = objectMapper.readValue(
                                evidence.getContentText(),
                                RepositoryResolutionResult.class
                        );
                        return new ReplayFixEvidenceSnapshot.RepositoryInfo(
                                result.projectKey(),
                                result.primaryRepositorySlug(),
                                "test2" // Hardcoded for now, should come from incident version
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse repository resolution: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private ReplayFixEvidenceSnapshot.JenkinsInfo buildJenkinsInfo(List<EvidenceEntity> allEvidence) {
        return allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                .findFirst()
                .map(evidence -> {
                    try {
                        JenkinsCaseEvidence jenkinsCase = objectMapper.readValue(
                                evidence.getContentText(),
                                JenkinsCaseEvidence.class
                        );
                        return new ReplayFixEvidenceSnapshot.JenkinsInfo(
                                jenkinsCase.build() != null ? jenkinsCase.build().jobName() : null,
                                jenkinsCase.build() != null ? jenkinsCase.build().buildNumber() : null,
                                jenkinsCase.build() != null ? jenkinsCase.build().commitSha() : null
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse Jenkins build context: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private ReplayFixEvidenceSnapshot.IncidentVersionInfo buildIncidentVersionInfo(List<EvidenceEntity> allEvidence) {
        return allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.INCIDENT_VERSION)
                .findFirst()
                .map(evidence -> {
                    try {
                        IncidentVersionResolution version = objectMapper.readValue(
                                evidence.getContentText(),
                                IncidentVersionResolution.class
                        );
                        return new ReplayFixEvidenceSnapshot.IncidentVersionInfo(
                                version.requestedSourceCommit(),
                                null, // bitbucketCommitSha - not currently tracked
                                version.resolvedCommitSha(),
                                version.exactMatch()
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse incident version: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private ReplayFixEvidenceSnapshot.RuntimeEvidenceInfo buildRuntimeEvidenceInfo(List<EvidenceEntity> allEvidence) {
        final List<String> extractedTraceIds = new ArrayList<>();
        final List<String> extractedOrderIds = new ArrayList<>();
        final List<String> extractedCorrelationIds = new ArrayList<>();

        // Extract Loki data
        LokiData lokiData = allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.LOKI_LOG)
                .findFirst()
                .map(loki -> {
                    try {
                        AdaptiveLokiSearchResult lokiResult = objectMapper.readValue(
                                loki.getContentText(),
                                AdaptiveLokiSearchResult.class
                        );
                        int matchedRows = 0;
                        int failedQueries = 0;
                        
                        if (lokiResult.logs() != null) {
                            matchedRows = lokiResult.logs().size();
                            
                            // Extract IDs from logs
                            for (var log : lokiResult.logs()) {
                                String line = log.line();
                                if (line != null) {
                                    extractIdFromLog(line, "traceId", extractedTraceIds);
                                    extractIdFromLog(line, "trace-id", extractedTraceIds);
                                    extractIdFromLog(line, "orderId", extractedOrderIds);
                                    extractIdFromLog(line, "order-id", extractedOrderIds);
                                    extractIdFromLog(line, "correlationId", extractedCorrelationIds);
                                    extractIdFromLog(line, "correlation-id", extractedCorrelationIds);
                                }
                            }
                        }
                        if (lokiResult.attempts() != null) {
                            failedQueries = (int) lokiResult.attempts().stream()
                                    .filter(attempt -> attempt.error() != null)
                                    .count();
                        }
                        
                        return new LokiData(matchedRows, failedQueries);
                    } catch (Exception e) {
                        log.warn("Failed to parse Loki evidence: {}", e.getMessage());
                        return new LokiData(0, 0);
                    }
                })
                .orElse(new LokiData(0, 0));

        // Extract Tempo data
        TempoData tempoData = allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE)
                .findFirst()
                .map(tempo -> new TempoData(extractedTraceIds.size(), 1))
                .orElse(new TempoData(0, 0));

        return new ReplayFixEvidenceSnapshot.RuntimeEvidenceInfo(
                lokiData.matchedRowCount,
                lokiData.failedQueryCount,
                extractedTraceIds.stream().distinct().limit(10).toList(),
                extractedOrderIds.stream().distinct().limit(10).toList(),
                extractedCorrelationIds.stream().distinct().limit(10).toList(),
                tempoData.requestedTraceCount,
                tempoData.foundTraceCount
        );
    }
    
    private record LokiData(int matchedRowCount, int failedQueryCount) {}
    private record TempoData(int requestedTraceCount, int foundTraceCount) {}

    private void extractIdFromLog(String line, String field, List<String> target) {
        int index = line.indexOf(field);
        if (index >= 0) {
            // Extract value after the field name (simple heuristic)
            String rest = line.substring(index + field.length());
            String[] parts = rest.split("[\\s,;\\[\\]{}\"']");
            for (String part : parts) {
                if (part.length() > 8 && !part.equals(field)) {
                    target.add(part.trim());
                    break;
                }
            }
        }
    }

    private ReplayFixEvidenceSnapshot.SourceContextInfo buildSourceContextInfo(List<EvidenceEntity> allEvidence) {
        return allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT)
                .findFirst()
                .map(evidence -> {
                    try {
                        SourceContextResult sourceResult = objectMapper.readValue(
                                evidence.getContentText(),
                                SourceContextResult.class
                        );
                        
                        List<ReplayFixEvidenceSnapshot.SourceContextInfo.MatchedFile> matchedFiles = 
                                new ArrayList<>();
                        
                        if (sourceResult.excerpts() != null) {
                            matchedFiles = sourceResult.excerpts().stream()
                                    .limit(5) // Limit to first 5 matches
                                    .map(excerpt -> new ReplayFixEvidenceSnapshot.SourceContextInfo.MatchedFile(
                                            excerpt.path(),
                                            excerpt.content() != null ? excerpt.content().substring(0, Math.min(200, excerpt.content().length())) : ""
                                    ))
                                    .toList();
                        }
                        
                        return new ReplayFixEvidenceSnapshot.SourceContextInfo(
                                sourceResult.scannedFileCount(),
                                sourceResult.excerpts() != null ? sourceResult.excerpts().size() : 0,
                                matchedFiles
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse source context: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(new ReplayFixEvidenceSnapshot.SourceContextInfo(0, 0, List.of()));
    }

    private ReplayFixEvidenceSnapshot.DeterministicRcaInfo buildDeterministicRcaInfo(List<EvidenceEntity> allEvidence) {
        return allEvidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .findFirst()
                .map(evidence -> {
                    try {
                        DeterministicRootCauseReport report = objectMapper.readValue(
                                evidence.getContentText(),
                                DeterministicRootCauseReport.class
                        );
                        
                        double confidence = evidence.getConfidence() != null ? evidence.getConfidence() : 0.0;
                        
                        return new ReplayFixEvidenceSnapshot.DeterministicRcaInfo(
                                report.classification(),
                                report.probableCause(),
                                confidence,
                                report.affectedApplications() != null ? report.affectedApplications() : List.of(),
                                report.supportingEvidence() != null ? report.supportingEvidence() : List.of(),
                                report.missingEvidence() != null ? report.missingEvidence() : List.of(),
                                report.recommendedActions() != null ? report.recommendedActions() : List.of()
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse deterministic RCA: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private ReplayFixEvidenceSnapshot.GuardrailsInfo buildGuardrailsInfo() {
        return new ReplayFixEvidenceSnapshot.GuardrailsInfo(
                true,  // evidenceOnly
                true,  // noAutomaticMerge
                true,  // noProductionDeployment
                true   // humanApprovalRequired
        );
    }
}

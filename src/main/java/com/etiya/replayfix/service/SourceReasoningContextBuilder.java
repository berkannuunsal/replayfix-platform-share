package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceDiffSnippet;
import com.etiya.replayfix.model.SourceFlowAnchor;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceRecentCommit;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SourceReasoningContextBuilder {

    public static final String SOURCE_REASONING_CONTEXT_TRUNCATED =
            "SOURCE_REASONING_CONTEXT_TRUNCATED";

    private static final int MAX_CONTEXT_CHARS = 80_000;
    private static final int MAX_ROVO_CHARS = 12_000;

    private final EvidenceRepository evidenceRepository;
    private final EvidenceSanitizer evidenceSanitizer;
    private final ObjectMapper objectMapper;

    public SourceReasoningContextBuilder(
            EvidenceRepository evidenceRepository,
            EvidenceSanitizer evidenceSanitizer,
            ObjectMapper objectMapper
    ) {
        this.evidenceRepository = evidenceRepository;
        this.evidenceSanitizer = evidenceSanitizer;
        this.objectMapper = objectMapper;
    }

    public ContextBuildResult build(
            ReplayCaseEntity replayCase,
            String repository,
            String branch,
            List<SourceFlowAnchor> flowAnchors,
            List<SourceCandidateFlowChainItem> candidateFlowChain,
            List<SourceCandidateMethod> candidateMethods,
            List<SourceRecentCommit> recentCommits,
            List<SourceDiffSnippet> diffSnippets
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        String rovo = latestEvidence(replayCase.getId(), EvidenceType.ROVO_RCA)
                .map(this::evidenceText)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> {
                    missingEvidence.add("ROVO_RCA");
                    return "";
                });
        rovo = truncate(evidenceSanitizer.sanitize(rovo), MAX_ROVO_CHARS);

        SourceReasoningContext context = new SourceReasoningContext(
                caseInfo(replayCase, repository, branch),
                jiraInfo(replayCase),
                rovo,
                flowAnchors,
                sanitizeChain(candidateFlowChain),
                sanitizeMethods(candidateMethods),
                sanitizeCommits(recentCommits),
                sanitizeDiffs(diffSnippets),
                missingEvidence,
                List.of(
                        "Use only provided evidence.",
                        "Do not scan or infer from the full repository.",
                        "Separate FACT, INFERENCE and UNKNOWN.",
                        "Keep status HYPOTHESIS unless replay/test/log evidence confirms."
                )
        );

        try {
            String serialized = objectMapper.writeValueAsString(context);
            if (serialized.length() > MAX_CONTEXT_CHARS) {
                warnings.add(SOURCE_REASONING_CONTEXT_TRUNCATED);
                context = truncateContext(context);
            }
        } catch (Exception ignored) {
            warnings.add(SOURCE_REASONING_CONTEXT_TRUNCATED);
        }

        return new ContextBuildResult(context, warnings);
    }

    private Map<String, Object> caseInfo(
            ReplayCaseEntity replayCase,
            String repository,
            String branch
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("caseId", replayCase.getId().toString());
        values.put("jiraKey", replayCase.getJiraKey());
        values.put("repository", repository);
        values.put("branch", branch);
        values.put("incidentCommitSha", replayCase.getSourceCommit());
        values.put("targetKey", replayCase.getTargetKey());
        return values;
    }

    private Map<String, Object> jiraInfo(ReplayCaseEntity replayCase) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jiraKey", replayCase.getJiraKey());
        values.put("orderId", replayCase.getOrderId());
        values.put("traceId", replayCase.getTraceId());
        return values;
    }

    private List<SourceCandidateFlowChainItem> sanitizeChain(
            List<SourceCandidateFlowChainItem> chain
    ) {
        return chain.stream()
                .map(item -> new SourceCandidateFlowChainItem(
                        item.layer(),
                        evidenceSanitizer.sanitize(item.file()),
                        evidenceSanitizer.sanitize(item.className()),
                        evidenceSanitizer.sanitize(item.methodName()),
                        sanitizeStrings(item.relatedSignals()),
                        evidenceSanitizer.sanitize(item.reason()),
                        item.status()
                ))
                .toList();
    }

    private List<SourceCandidateMethod> sanitizeMethods(
            List<SourceCandidateMethod> methods
    ) {
        return methods.stream()
                .map(method -> new SourceCandidateMethod(
                        evidenceSanitizer.sanitize(method.file()),
                        evidenceSanitizer.sanitize(method.className()),
                        evidenceSanitizer.sanitize(method.methodName()),
                        method.startLine(),
                        method.endLine(),
                        sanitizeStrings(method.relatedSignals()),
                        truncate(evidenceSanitizer.sanitize(method.snippet()), 3_000)
                ))
                .toList();
    }

    private List<SourceRecentCommit> sanitizeCommits(
            List<SourceRecentCommit> commits
    ) {
        return commits.stream()
                .map(commit -> new SourceRecentCommit(
                        commit.commitSha(),
                        commit.shortSha(),
                        evidenceSanitizer.sanitize(commit.author()),
                        commit.date(),
                        evidenceSanitizer.sanitize(commit.message()),
                        commit.jiraKeys(),
                        sanitizeStrings(commit.changedFiles()),
                        commit.touchedCandidateFile()
                ))
                .toList();
    }

    private List<SourceDiffSnippet> sanitizeDiffs(
            List<SourceDiffSnippet> snippets
    ) {
        return snippets.stream()
                .map(snippet -> new SourceDiffSnippet(
                        snippet.commitSha(),
                        evidenceSanitizer.sanitize(snippet.file()),
                        evidenceSanitizer.sanitize(snippet.methodName()),
                        truncate(evidenceSanitizer.sanitize(snippet.diff()), 3_000),
                        snippet.warnings()
                ))
                .toList();
    }

    private SourceReasoningContext truncateContext(SourceReasoningContext context) {
        return new SourceReasoningContext(
                context.caseInfo(),
                context.jira(),
                truncate(context.rovoRca(), 6_000),
                context.flowAnchors().stream().limit(40).toList(),
                context.candidateFlowChain().stream().limit(20).toList(),
                context.candidateMethods().stream().limit(20).toList(),
                context.recentCommits().stream().limit(30).toList(),
                context.diffSnippets().stream().limit(10).toList(),
                context.missingEvidence(),
                context.guardrails()
        );
    }

    private List<String> sanitizeStrings(List<String> values) {
        return values.stream()
                .map(evidenceSanitizer::sanitize)
                .toList();
    }

    private Optional<EvidenceEntity> latestEvidence(UUID caseId, EvidenceType type) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private String evidenceText(EvidenceEntity evidence) {
        if (evidence.getContentText() != null && !evidence.getContentText().isBlank()) {
            return evidence.getContentText();
        }
        return evidence.getBody() == null ? "" : evidence.getBody();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    public record ContextBuildResult(
            SourceReasoningContext context,
            List<String> warnings
    ) {
    }
}

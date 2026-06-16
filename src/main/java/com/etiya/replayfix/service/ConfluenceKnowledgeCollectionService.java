package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.ConfluenceClient;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConfluenceKnowledgeCollectionService {

    private static final String QUERY_PLAN_SOURCE = "confluence-knowledge-query-plan";
    private static final String CONTEXT_SOURCE = "confluence-knowledge-context";

    private final ConfluenceClient confluenceClient;
    private final ConfluenceKnowledgeQueryPlanner queryPlanner;
    private final ConfluenceKnowledgeRanker ranker;
    private final ConfluencePageTextExtractor textExtractor;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public ConfluenceKnowledgeCollectionService(
            ConfluenceClient confluenceClient,
            ConfluenceKnowledgeQueryPlanner queryPlanner,
            ConfluenceKnowledgeRanker ranker,
            ConfluencePageTextExtractor textExtractor,
            EvidenceService evidenceService,
            AuditService auditService,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.confluenceClient = confluenceClient;
        this.queryPlanner = queryPlanner;
        this.ranker = ranker;
        this.textExtractor = textExtractor;
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ConfluenceKnowledgeContext collect(UUID caseId) {
        List<String> warnings = new ArrayList<>();

        List<EvidenceEntity> evidence = evidenceService.list(caseId);

        ConfluenceKnowledgeQueryPlan queryPlan = queryPlanner.plan(caseId, evidence);

        saveQueryPlanEvidence(caseId, queryPlan);

        String applicationKey = extractApplicationKey(evidence);

        List<ConfluenceSearchHit> allHits = new ArrayList<>();
        int searchedQueryCount = 0;

        for (ConfluencePlannedQuery query : queryPlan.queries()) {
            try {
                ConfluenceSearchRequest request = new ConfluenceSearchRequest(
                        query.cql(),
                        query.limit()
                );

                ConfluenceSearchResponse response = confluenceClient.search(request);

                allHits.addAll(response.results());
                warnings.addAll(response.warnings());
                searchedQueryCount++;

            } catch (Exception exception) {
                warnings.add("Query failed: " + exception.getMessage());
            }
        }

        List<ConfluenceSearchHit> rankedHits = ranker.rank(allHits, queryPlan);

        int maxPagesPerCase = properties.getIntegrations().getConfluence().getMaxPagesPerCase();
        int maxTotalChars = properties.getIntegrations().getConfluence().getMaxTotalChars();

        List<ConfluenceSearchHit> selectedHits = new ArrayList<>();
        List<ConfluencePageDocument> pages = new ArrayList<>();
        int totalIncludedChars = 0;

        for (ConfluenceSearchHit hit : rankedHits) {
            if (selectedHits.size() >= maxPagesPerCase) {
                break;
            }

            if (totalIncludedChars >= maxTotalChars) {
                warnings.add("Reached max total chars limit");
                break;
            }

            try {
                ConfluencePageDocument page = confluenceClient.getPage(hit.pageId());

                if (page.plainText() == null || page.plainText().isBlank()) {
                    warnings.add("Empty page: " + hit.pageId());
                    continue;
                }

                page = textExtractor.extractText(page);

                if (totalIncludedChars + page.plainText().length() > maxTotalChars) {
                    int remaining = maxTotalChars - totalIncludedChars;
                    if (remaining > 1000) {
                        String truncated = page.plainText().substring(0, remaining);
                        page = new ConfluencePageDocument(
                                page.pageId(),
                                page.title(),
                                page.spaceId(),
                                page.spaceKey(),
                                page.status(),
                                page.webUrl(),
                                page.versionNumber(),
                                page.versionCreatedAt(),
                                page.bodyFormat(),
                                truncated,
                                page.originalLength(),
                                true,
                                page.labels(),
                                page.warnings()
                        );
                    } else {
                        break;
                    }
                }

                selectedHits.add(hit);
                pages.add(page);
                totalIncludedChars += page.plainText().length();

            } catch (Exception exception) {
                warnings.add("Failed to fetch page " + hit.pageId() + ": " + exception.getMessage());
            }
        }

        ConfluenceKnowledgeContext context = new ConfluenceKnowledgeContext(
                caseId,
                applicationKey,
                queryPlan,
                searchedQueryCount,
                allHits.size(),
                pages.size(),
                selectedHits,
                pages,
                totalIncludedChars,
                warnings
        );

        saveContextEvidence(caseId, context);

        auditService.record(
                caseId,
                "CONFLUENCE_KNOWLEDGE_COLLECTED",
                "replayfix-platform",
                "searched=" + searchedQueryCount
                        + ", hits=" + allHits.size()
                        + ", selected=" + pages.size()
                        + ", chars=" + totalIncludedChars
        );

        return context;
    }

    private String extractApplicationKey(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> "repository-resolution".equals(e.getSource()))
                .findFirst()
                .map(e -> {
                    String content = e.getContentText();
                    if (content != null && content.contains("repository")) {
                        int start = content.indexOf("\"repository\"");
                        if (start != -1) {
                            int valueStart = content.indexOf(":", start);
                            if (valueStart != -1) {
                                valueStart = content.indexOf("\"", valueStart);
                                if (valueStart != -1) {
                                    int valueEnd = content.indexOf("\"", valueStart + 1);
                                    if (valueEnd != -1) {
                                        return content.substring(valueStart + 1, valueEnd);
                                    }
                                }
                            }
                        }
                    }
                    return null;
                })
                .orElse("unknown");
    }

    private void saveQueryPlanEvidence(UUID caseId, ConfluenceKnowledgeQueryPlan queryPlan) {
        try {
            String json = objectMapper.writeValueAsString(queryPlan);

            evidenceService.save(
                    caseId,
                    EvidenceType.REPLAY_OUTPUT,
                    QUERY_PLAN_SOURCE,
                    json,
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Confluence query plan evidence.",
                    exception
            );
        }
    }

    private void saveContextEvidence(UUID caseId, ConfluenceKnowledgeContext context) {
        try {
            String json = objectMapper.writeValueAsString(context);

            evidenceService.save(
                    caseId,
                    EvidenceType.REPLAY_OUTPUT,
                    CONTEXT_SOURCE,
                    json,
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Confluence knowledge context evidence.",
                    exception
            );
        }
    }
}

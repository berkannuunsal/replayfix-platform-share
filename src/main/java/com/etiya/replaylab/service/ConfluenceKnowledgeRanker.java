package com.etiya.replaylab.service;

import com.etiya.replaylab.model.ConfluenceKnowledgeQueryPlan;
import com.etiya.replaylab.model.ConfluenceSearchHit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfluenceKnowledgeRanker {

    public List<ConfluenceSearchHit> rank(
            List<ConfluenceSearchHit> hits,
            ConfluenceKnowledgeQueryPlan queryPlan
    ) {
        Map<String, ConfluenceSearchHit> uniqueHits = new LinkedHashMap<>();

        for (ConfluenceSearchHit hit : hits) {
            if (!uniqueHits.containsKey(hit.pageId())) {
                int score = calculateScore(hit, queryPlan);
                List<String> matchReasons = calculateMatchReasons(hit, queryPlan);

                uniqueHits.put(
                        hit.pageId(),
                        new ConfluenceSearchHit(
                                hit.pageId(),
                                hit.title(),
                                hit.spaceKey(),
                                hit.spaceName(),
                                hit.url(),
                                hit.excerpt(),
                                hit.lastModified(),
                                hit.versionNumber(),
                                hit.apiScore(),
                                score,
                                matchReasons
                        )
                );
            }
        }

        return uniqueHits.values().stream()
                .sorted(Comparator.comparingInt(ConfluenceSearchHit::replayLabScore).reversed())
                .collect(Collectors.toList());
    }

    private int calculateScore(
            ConfluenceSearchHit hit,
            ConfluenceKnowledgeQueryPlan queryPlan
    ) {
        int score = 0;

        String titleLower = hit.title() != null ? hit.title().toLowerCase() : "";
        String excerptLower = hit.excerpt() != null ? hit.excerpt().toLowerCase() : "";

        for (String term : queryPlan.searchTerms()) {
            String termLower = term.toLowerCase();
            if (titleLower.contains(termLower)) {
                score += 120;
            }
            if (excerptLower.contains(termLower)) {
                score += 60;
            }
        }

        for (String error : queryPlan.errorTerms()) {
            String errorLower = error.toLowerCase();
            if (titleLower.contains(errorLower) || excerptLower.contains(errorLower)) {
                score += 100;
            }
        }

        for (String endpoint : queryPlan.endpointTerms()) {
            String endpointLower = endpoint.toLowerCase();
            if (titleLower.contains(endpointLower) || excerptLower.contains(endpointLower)) {
                score += 100;
            }
        }

        for (String service : queryPlan.serviceTerms()) {
            String serviceLower = service.toLowerCase();
            if (titleLower.contains(serviceLower)) {
                score += 80;
            }
            if (excerptLower.contains(serviceLower)) {
                score += 40;
            }
        }

        if (titleLower.contains("runbook") || titleLower.contains("known issue")) {
            score += 40;
        }

        if (titleLower.contains("api") || titleLower.contains("technical")) {
            score += 20;
        }

        if (hit.lastModified() != null && !hit.lastModified().isBlank()) {
            try {
                Instant lastMod = Instant.parse(hit.lastModified());
                Instant eighteenMonthsAgo = Instant.now().minus(18 * 30, ChronoUnit.DAYS);

                if (lastMod.isAfter(eighteenMonthsAgo)) {
                    score += 20;
                }
            } catch (Exception ignored) {
            }
        }

        if (queryPlan.allowedSpaceKeys() != null
                && !queryPlan.allowedSpaceKeys().isEmpty()
                && queryPlan.allowedSpaceKeys().contains(hit.spaceKey())) {
            score += 20;
        }

        if (titleLower.contains("archived") || titleLower.contains("outdated") || titleLower.contains("deprecated")) {
            score -= 50;
        }

        if (titleLower.contains("old") || titleLower.contains("legacy")) {
            score -= 20;
        }

        return Math.max(0, score);
    }

    private List<String> calculateMatchReasons(
            ConfluenceSearchHit hit,
            ConfluenceKnowledgeQueryPlan queryPlan
    ) {
        List<String> reasons = new ArrayList<>();

        String titleLower = hit.title() != null ? hit.title().toLowerCase() : "";
        String excerptLower = hit.excerpt() != null ? hit.excerpt().toLowerCase() : "";

        for (String term : queryPlan.searchTerms()) {
            if (titleLower.contains(term.toLowerCase())) {
                reasons.add("Title contains: " + term);
            }
        }

        for (String error : queryPlan.errorTerms()) {
            if (titleLower.contains(error.toLowerCase()) || excerptLower.contains(error.toLowerCase())) {
                reasons.add("Error code match: " + error);
            }
        }

        for (String endpoint : queryPlan.endpointTerms()) {
            if (titleLower.contains(endpoint.toLowerCase()) || excerptLower.contains(endpoint.toLowerCase())) {
                reasons.add("Endpoint match: " + endpoint);
            }
        }

        for (String service : queryPlan.serviceTerms()) {
            if (titleLower.contains(service.toLowerCase())) {
                reasons.add("Service match: " + service);
            }
        }

        if (titleLower.contains("runbook")) {
            reasons.add("Runbook document");
        }

        if (titleLower.contains("known issue")) {
            reasons.add("Known issue document");
        }

        return reasons;
    }
}

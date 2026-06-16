package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.model.ConfluenceKnowledgeQueryPlan;
import com.etiya.replayfix.model.ConfluencePlannedQuery;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfluenceKnowledgeQueryPlanner {

    private static final int MAX_TERM_LENGTH = 100;
    private static final int MAX_QUERIES = 10;

    private final ReplayFixProperties properties;

    public ConfluenceKnowledgeQueryPlanner(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public ConfluenceKnowledgeQueryPlan plan(
            UUID caseId,
            List<EvidenceEntity> evidence
    ) {
        List<String> warnings = new ArrayList<>();

        String jiraSummary = extractJiraSummary(evidence);
        String jiraDescription = extractJiraDescription(evidence);
        String jiraKey = extractJiraKey(evidence);
        String applicationKey = extractApplicationKey(evidence);
        List<String> errorCodes = extractErrorCodes(evidence);
        List<String> endpointPaths = extractEndpointPaths(evidence);
        List<String> services = extractServices(evidence);

        Set<String> searchTerms = new LinkedHashSet<>();
        Set<String> serviceTerms = new LinkedHashSet<>();
        Set<String> errorTerms = new LinkedHashSet<>();
        Set<String> endpointTerms = new LinkedHashSet<>();

        if (jiraKey != null && !jiraKey.isBlank()) {
            searchTerms.add(sanitizeTerm(jiraKey));
        }

        if (applicationKey != null && !applicationKey.isBlank()) {
            serviceTerms.add(sanitizeTerm(applicationKey));
        }

        for (String service : services) {
            String sanitized = sanitizeTerm(service);
            if (sanitized != null && !sanitized.isBlank()) {
                serviceTerms.add(sanitized);
            }
        }

        for (String error : errorCodes) {
            String sanitized = sanitizeTerm(error);
            if (sanitized != null && !sanitized.isBlank()) {
                errorTerms.add(sanitized);
            }
        }

        for (String endpoint : endpointPaths) {
            String sanitized = sanitizeTerm(endpoint);
            if (sanitized != null && !sanitized.isBlank()) {
                endpointTerms.add(sanitized);
            }
        }

        extractKeywordsFromText(jiraSummary, searchTerms);
        extractKeywordsFromText(jiraDescription, searchTerms);

        List<String> allowedSpaceKeys = extractAllowedSpaceKeys();

        List<ConfluencePlannedQuery> queries = buildQueries(
                searchTerms,
                serviceTerms,
                errorTerms,
                endpointTerms,
                allowedSpaceKeys,
                warnings
        );

        return new ConfluenceKnowledgeQueryPlan(
                caseId,
                new ArrayList<>(searchTerms),
                new ArrayList<>(serviceTerms),
                new ArrayList<>(errorTerms),
                new ArrayList<>(endpointTerms),
                allowedSpaceKeys,
                queries,
                warnings
        );
    }

    private List<ConfluencePlannedQuery> buildQueries(
            Set<String> searchTerms,
            Set<String> serviceTerms,
            Set<String> errorTerms,
            Set<String> endpointTerms,
            List<String> allowedSpaceKeys,
            List<String> warnings
    ) {
        List<ConfluencePlannedQuery> queries = new ArrayList<>();

        int maxResults = properties.getIntegrations().getConfluence().getMaxSearchResults();

        for (String term : searchTerms) {
            if (queries.size() >= MAX_QUERIES) {
                warnings.add("Reached max query limit");
                break;
            }

            String cql = buildCql("Jira key or keyword", term, allowedSpaceKeys);
            queries.add(new ConfluencePlannedQuery(
                    "Search by Jira key or keyword",
                    cql,
                    maxResults,
                    100
            ));
        }

        for (String error : errorTerms) {
            if (queries.size() >= MAX_QUERIES) {
                break;
            }

            String cql = buildCql("Error code", error, allowedSpaceKeys);
            queries.add(new ConfluencePlannedQuery(
                    "Search by error code",
                    cql,
                    maxResults,
                    90
            ));
        }

        for (String endpoint : endpointTerms) {
            if (queries.size() >= MAX_QUERIES) {
                break;
            }

            String cql = buildCql("Endpoint path", endpoint, allowedSpaceKeys);
            queries.add(new ConfluencePlannedQuery(
                    "Search by endpoint",
                    cql,
                    maxResults,
                    85
            ));
        }

        for (String service : serviceTerms) {
            if (queries.size() >= MAX_QUERIES) {
                break;
            }

            String cql = buildCql("Service name", service, allowedSpaceKeys);
            queries.add(new ConfluencePlannedQuery(
                    "Search by service",
                    cql,
                    maxResults,
                    80
            ));
        }

        return queries;
    }

    private String buildCql(
            String purpose,
            String term,
            List<String> allowedSpaceKeys
    ) {
        String escapedTerm = escapeCqlTerm(term);

        StringBuilder cql = new StringBuilder("type=page");

        if (allowedSpaceKeys != null && !allowedSpaceKeys.isEmpty()) {
            String spaceList = allowedSpaceKeys.stream()
                    .map(this::escapeCqlValue)
                    .collect(Collectors.joining(","));
            cql.append(" AND space in (").append(spaceList).append(")");
        }

        cql.append(" AND text~\"").append(escapedTerm).append("\"");

        return cql.toString();
    }

    private String escapeCqlTerm(String term) {
        if (term == null || term.isBlank()) {
            return "";
        }

        return term
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", "");
    }

    private String escapeCqlValue(String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }

        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return "\"" + escaped + "\"";
    }

    private String sanitizeTerm(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }

        String sanitized = term.trim();

        if (sanitized.length() > MAX_TERM_LENGTH) {
            sanitized = sanitized.substring(0, MAX_TERM_LENGTH);
        }

        sanitized = sanitized.replaceAll("[\\p{Cntrl}]", "");

        if (containsDangerousKeywords(sanitized)) {
            return null;
        }

        return sanitized;
    }

    private boolean containsDangerousKeywords(String term) {
        String lower = term.toLowerCase();
        return lower.contains("creator")
                || lower.contains("contributor")
                || lower.contains("accountid")
                || lower.contains("permission");
    }

    private void extractKeywordsFromText(String text, Set<String> keywords) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] words = text.split("\\s+");

        for (String word : words) {
            if (word.length() >= 4 && word.length() <= 30) {
                String sanitized = sanitizeTerm(word);
                if (sanitized != null && !sanitized.isBlank()) {
                    keywords.add(sanitized);
                }
            }

            if (keywords.size() >= 10) {
                break;
            }
        }
    }

    private String extractJiraSummary(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> "jira-issue".equals(e.getSource()))
                .findFirst()
                .map(EvidenceEntity::getContentText)
                .orElse("");
    }

    private String extractJiraDescription(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> "jira-description-plain".equals(e.getSource()))
                .findFirst()
                .map(EvidenceEntity::getContentText)
                .orElse("");
    }

    private String extractJiraKey(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> "jira-issue".equals(e.getSource()))
                .findFirst()
                .map(e -> extractFieldFromJson(e.getContentText(), "key"))
                .orElse(null);
    }

    private String extractApplicationKey(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> "repository-resolution".equals(e.getSource()))
                .findFirst()
                .map(e -> extractFieldFromJson(e.getContentText(), "repository"))
                .orElse(null);
    }

    private List<String> extractErrorCodes(List<EvidenceEntity> evidence) {
        List<String> errors = new ArrayList<>();

        for (EvidenceEntity entity : evidence) {
            String content = entity.getContentText();
            if (content == null) {
                continue;
            }

            if (content.contains("401")) errors.add("401");
            if (content.contains("403")) errors.add("403");
            if (content.contains("404")) errors.add("404");
            if (content.contains("500")) errors.add("500");
            if (content.contains("502")) errors.add("502");
            if (content.contains("503")) errors.add("503");
        }

        return errors.stream().distinct().collect(Collectors.toList());
    }

    private List<String> extractEndpointPaths(List<EvidenceEntity> evidence) {
        List<String> endpoints = new ArrayList<>();

        for (EvidenceEntity entity : evidence) {
            String content = entity.getContentText();
            if (content == null) {
                continue;
            }

            if (content.contains("/api/")) {
                String[] parts = content.split("\\s+");
                for (String part : parts) {
                    if (part.contains("/api/") && part.length() < 50) {
                        endpoints.add(part);
                    }
                }
            }
        }

        return endpoints.stream().distinct().limit(5).collect(Collectors.toList());
    }

    private List<String> extractServices(List<EvidenceEntity> evidence) {
        List<String> services = new ArrayList<>();

        for (EvidenceEntity entity : evidence) {
            if ("tempo-trace-collection-summary".equals(entity.getSource())) {
                String content = entity.getContentText();
                if (content != null && content.contains("probableFailureServices")) {
                    services.add(extractFieldFromJson(content, "probableFailureServices"));
                }
            }
        }

        return services.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> extractAllowedSpaceKeys() {
        String configValue = properties.getIntegrations().getConfluence().getAllowedSpaceKeys();

        if (configValue == null || configValue.isBlank()) {
            return List.of();
        }

        return Arrays.stream(configValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private String extractFieldFromJson(String json, String field) {
        if (json == null || !json.contains(field)) {
            return null;
        }

        int start = json.indexOf("\"" + field + "\"");
        if (start == -1) {
            return null;
        }

        int valueStart = json.indexOf(":", start);
        if (valueStart == -1) {
            return null;
        }

        valueStart = json.indexOf("\"", valueStart);
        if (valueStart == -1) {
            return null;
        }

        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd == -1) {
            return null;
        }

        return json.substring(valueStart + 1, valueEnd);
    }
}

package com.etiya.replaylab.service;

import com.etiya.replaylab.config.RepositoryResolutionProperties;
import com.etiya.replaylab.config.RepositoryResolutionProperties.RepositoryRule;
import com.etiya.replaylab.model.BitbucketRepositoryInfo;
import com.etiya.replaylab.model.IncidentSignals;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import com.etiya.replaylab.model.RepositoryCandidate;
import com.etiya.replaylab.model.RepositoryResolutionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RepositoryResolverService {

    private final RepositoryResolutionProperties properties;

    public RepositoryResolverService(
            RepositoryResolutionProperties properties
    ) {
        this.properties = properties;
    }

    public RepositoryResolutionResult resolve(
            List<BitbucketRepositoryInfo> repositories,
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals signals,
            IncidentTimeline timeline
    ) {
        if (repositories == null || repositories.isEmpty()) {
            return new RepositoryResolutionResult(
                    "",
                    "",
                    List.of(),
                    collectUnresolvedSignals(signals),
                    "Bitbucket repository list is empty."
            );
        }

        String searchableText = buildSearchableText(
                jiraIssue,
                plainDescription,
                signals,
                timeline
        );

        List<RepositoryCandidate> candidates =
                repositories.stream()
                        .filter(repository ->
                                !repository.archived()
                        )
                        .map(repository ->
                                scoreRepository(
                                        repository,
                                        searchableText,
                                        signals
                                )
                        )
                        .filter(candidate ->
                                candidate.score()
                                        >= properties
                                        .getMinimumScore()
                        )
                        .sorted(
                                Comparator.comparingInt(
                                                RepositoryCandidate::score
                                        )
                                        .reversed()
                        )
                        .limit(
                                properties.getMaxCandidates()
                        )
                        .toList();

        String primary = candidates.isEmpty()
                ? ""
                : candidates.get(0).slug();

        String warning = candidates.isEmpty()
                ? "No repository reached the minimum score."
                : "";

        return new RepositoryResolutionResult(
                repositories.get(0).projectKey(),
                primary,
                candidates,
                candidates.isEmpty()
                        ? collectUnresolvedSignals(signals)
                        : List.of(),
                warning
        );
    }

    private RepositoryCandidate scoreRepository(
            BitbucketRepositoryInfo repository,
            String searchableText,
            IncidentSignals signals
    ) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        RepositoryRule rule = findRule(repository);

        String normalizedSlug = normalize(
                repository.slug()
        );
        String normalizedName = normalize(
                repository.name()
        );

        if (searchableText.contains(normalizedSlug)
                || searchableText.contains(normalizedName)) {
            score += 35;
            reasons.add(
                    "Repository name appears in incident context."
            );
        }

        if (rule != null) {
            for (String alias : safe(rule.getAliases())) {
                String normalizedAlias = normalize(alias);

                if (searchableText.contains(
                        normalizedAlias
                )) {
                    score += 40;
                    reasons.add(
                            "Alias matched: " + alias
                    );
                }

                if (containsIgnoreCase(
                        signals == null
                                ? List.of()
                                : signals.serviceHints(),
                        alias
                )) {
                    score += 100;
                    reasons.add(
                            "Service hint matched: " + alias
                    );
                }
            }

            for (String prefix :
                    safe(rule.getEndpointPrefixes())) {

                boolean matched = signals != null
                        && safe(signals.endpoints())
                        .stream()
                        .anyMatch(endpoint ->
                                endpoint.toLowerCase(
                                                Locale.ROOT
                                        )
                                        .startsWith(
                                                prefix.toLowerCase(
                                                        Locale.ROOT
                                                )
                                        )
                        );

                if (matched) {
                    score += 70;
                    reasons.add(
                            "Endpoint prefix matched: "
                                    + prefix
                    );
                }
            }

            for (String keyword :
                    safe(rule.getKeywords())) {

                if (searchableText.contains(
                        normalize(keyword)
                )) {
                    score += 20;
                    reasons.add(
                            "Keyword matched: " + keyword
                    );
                }
            }
        }

        return new RepositoryCandidate(
                repository.projectKey(),
                repository.slug(),
                repository.name(),
                repository.defaultBranch(),
                repository.cloneUrl(),
                score,
                reasons.stream()
                        .distinct()
                        .toList()
        );
    }

    private RepositoryRule findRule(
            BitbucketRepositoryInfo repository
    ) {
        return properties.getMappings()
                .entrySet()
                .stream()
                .filter(entry ->
                        normalize(entry.getKey())
                                .equals(
                                        normalize(
                                                repository.slug()
                                        )
                                )
                                || normalize(entry.getKey())
                                .equals(
                                        normalize(
                                                repository.name()
                                        )
                                )
                )
                .map(entry -> entry.getValue())
                .findFirst()
                .orElse(null);
    }

    private String buildSearchableText(
            JiraIssue issue,
            String plainDescription,
            IncidentSignals signals,
            IncidentTimeline timeline
    ) {
        StringBuilder text = new StringBuilder();

        if (issue != null) {
            append(text, issue.key());
            append(text, issue.summary());
        }

        append(text, plainDescription);

        if (signals != null) {
            safe(signals.endpoints())
                    .forEach(value ->
                            append(text, value)
                    );

            safe(signals.businessTerms())
                    .forEach(value ->
                            append(text, value)
                    );

            safe(signals.serviceHints())
                    .forEach(value ->
                            append(text, value)
                    );

            safe(signals.errorCodes())
                    .forEach(value ->
                            append(text, value)
                    );
        }

        if (timeline != null
                && timeline.events() != null) {
            timeline.events()
                    .stream()
                    .limit(100)
                    .forEach(event -> {
                        append(
                                text,
                                event.application()
                        );
                        append(
                                text,
                                event.endpoint()
                        );
                        append(
                                text,
                                event.message()
                        );
                    });
        }

        return normalize(text.toString());
    }

    private List<String> collectUnresolvedSignals(
            IncidentSignals signals
    ) {
        if (signals == null) {
            return List.of();
        }

        Set<String> values = new LinkedHashSet<>();

        values.addAll(safe(signals.serviceHints()));
        values.addAll(safe(signals.endpoints()));
        values.addAll(safe(signals.businessTerms()));

        return new ArrayList<>(values);
    }

    private void append(
            StringBuilder target,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            target.append(' ')
                    .append(value);
        }
    }

    private boolean containsIgnoreCase(
            List<String> values,
            String expected
    ) {
        if (values == null || expected == null) {
            return false;
        }

        String normalizedExpected = normalize(expected);

        return values.stream()
                .anyMatch(value ->
                        normalize(value)
                                .equals(normalizedExpected)
                );
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9/_-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}

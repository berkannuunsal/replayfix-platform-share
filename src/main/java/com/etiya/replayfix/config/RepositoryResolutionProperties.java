package com.etiya.replayfix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(
        prefix = "replayfix.repository-resolution"
)
public class RepositoryResolutionProperties {

    private int minimumScore = 30;
    private int maxCandidates = 4;
    private Map<String, RepositoryRule> mappings =
            new LinkedHashMap<>();

    public int getMinimumScore() {
        return minimumScore;
    }

    public void setMinimumScore(int minimumScore) {
        this.minimumScore = minimumScore;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public Map<String, RepositoryRule> getMappings() {
        return mappings;
    }

    public void setMappings(
            Map<String, RepositoryRule> mappings
    ) {
        this.mappings = mappings;
    }

    public static class RepositoryRule {
        private List<String> aliases = new ArrayList<>();
        private List<String> endpointPrefixes =
                new ArrayList<>();
        private List<String> keywords = new ArrayList<>();

        public List<String> getAliases() {
            return aliases;
        }

        public void setAliases(List<String> aliases) {
            this.aliases = aliases;
        }

        public List<String> getEndpointPrefixes() {
            return endpointPrefixes;
        }

        public void setEndpointPrefixes(
                List<String> endpointPrefixes
        ) {
            this.endpointPrefixes = endpointPrefixes;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }
    }
}

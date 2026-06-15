package com.etiya.replayfix.service;

import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.LokiQueryCandidate;
import com.etiya.replayfix.model.LokiQueryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LokiQueryPlanner {

    public LokiQueryPlan plan(
        String jiraKey,
        String summary,
        String plainDescription,
        IncidentSignals signals
    ) {
        String selector = buildApplicationSelector(
            signals.serviceHints()
        );

        Map<String, LokiQueryCandidate> queries =
            new LinkedHashMap<>();

        for (String endpoint : signals.endpoints()) {
            for (String httpStatus : signals.httpStatuses()) {
                add(
                    queries,
                    100,
                    "Endpoint and HTTP status combination",
                    selector
                        + " |= \""
                        + escape(endpoint)
                        + "\" |= \""
                        + escape(httpStatus)
                        + "\""
                );
            }

            add(
                queries,
                90,
                "Endpoint detected in Jira description",
                selector
                    + " |= \""
                    + escape(endpoint)
                    + "\""
            );
        }

        for (String term : signals.businessTerms()) {
            add(
                queries,
                80,
                "Business or technical term detected: " + term,
                selector
                    + " |= \""
                    + escape(term)
                    + "\""
            );
        }

        for (String errorCode : signals.errorCodes()) {
            add(
                queries,
                75,
                "Error code detected in Jira description",
                selector
                    + " |= \""
                    + escape(errorCode)
                    + "\""
            );
        }

        for (String statusValue : signals.statusValues()) {
            add(
                queries,
                65,
                "Order status detected in Jira description",
                selector
                    + " |~ \"(?i)status\\\\s*[:=]?\\\\s*"
                    + escape(statusValue)
                    + "\""
            );
        }

        // En geniş fallback sorgusu.
        add(
            queries,
            20,
            "Fallback search using Jira key",
            selector
                + " |= \""
                + escape(jiraKey)
                + "\""
        );

        return new LokiQueryPlan(
            jiraKey,
            summary,
            plainDescription,
            signals,
            new ArrayList<>(queries.values())
        );
    }

    private String buildApplicationSelector(List<String> applications) {
        String regex = String.join("|", applications);

        return "{app=~\"(" + regex + ")\"}";
    }

    private void add(
        Map<String, LokiQueryCandidate> queries,
        int priority,
        String reason,
        String logQl
    ) {
        queries.putIfAbsent(
            logQl,
            new LokiQueryCandidate(
                priority,
                reason,
                logQl
            )
        );
    }

    private String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}

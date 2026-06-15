package com.etiya.replayfix.service;

import com.etiya.replayfix.model.IncidentSignals;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IncidentSignalExtractor {

    private static final Pattern ENDPOINT_PATTERN =
        Pattern.compile(
            "(?<![A-Za-z0-9])(/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+)"
        );

    private static final Pattern HTTP_STATUS_PATTERN =
        Pattern.compile(
            "(?i)\\bHTTP\\s*(4\\d\\d|5\\d\\d)\\b"
        );

    private static final Pattern ERROR_CODE_PATTERN =
        Pattern.compile(
            "(?i)\\b(\\d{4,6})\\s*(?:ERROR|ERR)\\b"
                + "|\\b(?:ERROR|ERR)\\s*[:=]?\\s*(\\d{4,6})\\b"
        );

    private static final Pattern STATUS_VALUE_PATTERN =
        Pattern.compile(
            "(?i)\\bstatus\\s*(?:=|:|is|in)?\\s*(\\d{1,3})\\b"
        );

    private static final List<String> BUSINESS_TERMS = List.of(
        "RECURRING_PAYMENT_CALLBACK",
        "BAR",
        "Camunda",
        "Unauthorized",
        "customerorder",
        "payment"
    );

    public IncidentSignals extract(String text) {
        String safeText = text == null ? "" : text;

        Set<String> endpoints = extractEndpoints(safeText);
        Set<String> httpStatuses =
            extractValues(safeText, HTTP_STATUS_PATTERN);

        Set<String> errorCodes =
            extractValues(safeText, ERROR_CODE_PATTERN);

        Set<String> statusValues =
            extractValues(safeText, STATUS_VALUE_PATTERN);

        Set<String> businessTerms = new LinkedHashSet<>();

        for (String term : BUSINESS_TERMS) {
            if (safeText.toLowerCase(Locale.ROOT)
                .contains(term.toLowerCase(Locale.ROOT))) {
                businessTerms.add(term);
            }
        }

        Set<String> serviceHints = determineApplications(safeText);

        return new IncidentSignals(
            new ArrayList<>(endpoints),
            new ArrayList<>(httpStatuses),
            new ArrayList<>(errorCodes),
            new ArrayList<>(businessTerms),
            new ArrayList<>(statusValues),
            new ArrayList<>(serviceHints)
        );
    }

    private Set<String> extractEndpoints(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = ENDPOINT_PATTERN.matcher(text);

        while (matcher.find()) {
            String endpoint = matcher.group(1)
                .replaceAll("[.,;:]+$", "");

            if (endpoint.length() > 2
                && !endpoint.startsWith("//")
                && endpoint.matches("/[A-Za-z0-9].*")) {
                values.add(endpoint);
            }
        }

        return values;
    }

    private Set<String> extractValues(
        String text,
        Pattern pattern
    ) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);

                if (value != null && !value.isBlank()) {
                    values.add(value);
                    break;
                }
            }
        }

        return values;
    }

    private Set<String> determineApplications(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> applications = new LinkedHashSet<>();

        if (containsAny(
            lower,
            "customerorder",
            "bar",
            "payment",
            "unauthorized",
            "omintegration"
        )) {
            applications.add("bss-backend");
        }

        if (containsAny(
            lower,
            "recurring",
            "callback",
            "batch",
            "stuck order"
        )) {
            applications.add("bss-backend-batch");
        }

        if (containsAny(
            lower,
            "notification",
            "notify",
            "message"
        )) {
            applications.add("ntf");
        }

        if (containsAny(
            lower,
            "loyalty",
            "reward",
            "campaign"
        )) {
            applications.add("loyalty");
        }

        if (applications.isEmpty()) {
            applications.add("bss-backend");
            applications.add("bss-backend-batch");
            applications.add("ntf");
            applications.add("loyalty");
        }

        return applications;
    }

    private boolean containsAny(
        String text,
        String... candidates
    ) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }

        return false;
    }
}

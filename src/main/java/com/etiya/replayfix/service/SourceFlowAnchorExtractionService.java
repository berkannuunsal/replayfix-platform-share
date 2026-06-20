package com.etiya.replayfix.service;

import com.etiya.replayfix.model.SourceFlowAnchor;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SourceFlowAnchorExtractionService {

    private static final Set<String> GENERIC_STANDALONE = Set.of(
            "user",
            "account",
            "billing",
            "region",
            "update",
            "tax"
    );

    public List<SourceFlowAnchor> extract(List<SuspectSourceSignal> signals) {
        Map<String, SourceFlowAnchor> anchors = new LinkedHashMap<>();

        for (SuspectSourceSignal signal : signals) {
            if (signal.value() == null || signal.value().isBlank()) {
                continue;
            }

            String value = signal.value().trim();
            if (!isStrongAnchor(signal, value)) {
                continue;
            }

            String key = value.toLowerCase(Locale.ROOT);
            anchors.putIfAbsent(
                    key,
                    new SourceFlowAnchor(
                            value,
                            anchorType(signal, value),
                            anchorReason(signal, value)
                    )
            );
        }

        return anchors.values().stream().toList();
    }

    private boolean isStrongAnchor(SuspectSourceSignal signal, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (GENERIC_STANDALONE.contains(normalized)) {
            return false;
        }

        if (value.startsWith("/") && value.length() > 1) {
            return true;
        }

        if (signal.category() == SuspectSignalCategory.CONSTANT) {
            return true;
        }

        if (value.matches("[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+")) {
            return true;
        }

        if (value.matches("[A-Z][A-Za-z0-9]+(?:[A-Z][A-Za-z0-9]+)+")) {
            return true;
        }

        if (value.matches("[a-z]+(?:[A-Z][a-z0-9]+)+")) {
            return true;
        }

        if (value.contains(" ")
                && (normalized.contains("flow")
                || normalized.contains("mismatch")
                || normalized.contains("province")
                || normalized.contains("timezone")
                || normalized.contains("tax info"))) {
            return true;
        }

        return signal.strength() == SuspectSignalStrength.STRONG
                && value.length() >= 8
                && !GENERIC_STANDALONE.contains(normalized);
    }

    private String anchorType(SuspectSourceSignal signal, String value) {
        if (value.startsWith("/")) {
            return "ENDPOINT";
        }
        if (signal.category() == SuspectSignalCategory.CONSTANT
                || value.matches("[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+")) {
            return "CONSTANT";
        }
        if (value.contains(" ")) {
            return "BUSINESS_FLOW";
        }
        return "DOMAIN_OBJECT";
    }

    private String anchorReason(SuspectSourceSignal signal, String value) {
        if (value.startsWith("/")) {
            return "Endpoint path anchor from suspect signals.";
        }
        if (signal.category() == SuspectSignalCategory.CONSTANT) {
            return "Constant anchor from suspect signals.";
        }
        if (value.contains(" ")) {
            return "Exact business flow or mismatch phrase anchor.";
        }
        return "Domain object or operation-domain anchor.";
    }
}

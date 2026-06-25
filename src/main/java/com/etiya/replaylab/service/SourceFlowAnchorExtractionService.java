package com.etiya.replaylab.service;

import com.etiya.replaylab.model.SourceFlowAnchor;
import com.etiya.replaylab.model.SuspectSignalCategory;
import com.etiya.replaylab.model.SuspectSourceSignal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SourceFlowAnchorExtractionService {

    private static final Set<String> GENERIC_STANDALONE = Set.of(
            "initialize",
            "businessflow",
            "business_flow",
            "business-flow",
            "/initialize",
            "/businessflow",
            "user",
            "account",
            "billing",
            "billingaccount",
            "region",
            "update",
            "tax",
            "flow",
            "gl",
            "i2i",
            "executivesummaryi2ifatura",
            "glraporu"
    );

    private static final Set<String> PRIMARY_ANCHORS = Set.of(
            "/businessflow/initialize",
            "/user/region/update",
            "/region",
            "preferred_province",
            "preferredprovince",
            "taxinfo",
            "timezone",
            "billing account creation / update flow",
            "preferred province",
            "region mismatch",
            "timezone mismatch",
            "tax info mismatch"
    );

    public List<SourceFlowAnchor> extract(List<SuspectSourceSignal> signals) {
        Map<String, SourceFlowAnchor> anchors = new LinkedHashMap<>();

        for (SuspectSourceSignal signal : signals) {
            if (signal.value() == null || signal.value().isBlank()) {
                continue;
            }

            String value = signal.value().trim();
            AnchorClassification classification = classify(signal, value);
            if (classification == null) {
                continue;
            }

            String key = value.toLowerCase(Locale.ROOT);
            anchors.putIfAbsent(
                    key,
                    new SourceFlowAnchor(
                            value,
                            anchorType(signal, value),
                            classification.strength(),
                            classification.primary(),
                            anchorReason(signal, value)
                    )
            );
        }

        return anchors.values().stream().toList();
    }

    private AnchorClassification classify(SuspectSourceSignal signal, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("BillingAccount".equals(value)) {
            return new AnchorClassification("STRONG", true);
        }
        if (PRIMARY_ANCHORS.contains(normalized)) {
            return new AnchorClassification("STRONG", true);
        }
        if (GENERIC_STANDALONE.contains(normalized)) {
            return new AnchorClassification("WEAK", false);
        }

        return null;
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

    private record AnchorClassification(
            String strength,
            boolean primary
    ) {
    }
}

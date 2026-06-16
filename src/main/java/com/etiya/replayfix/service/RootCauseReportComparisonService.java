package com.etiya.replayfix.service;

import com.etiya.replayfix.model.DeterministicRootCauseReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class RootCauseReportComparisonService {

    public List<String> compare(
            DeterministicRootCauseReport previous,
            DeterministicRootCauseReport refreshed
    ) {
        List<String> changes =
                new ArrayList<>();

        if (previous == null) {
            changes.add(
                    "No previous deterministic root-cause report was found."
            );

            return changes;
        }

        if (!Objects.equals(
                previous.probableCause(),
                refreshed.probableCause()
        )) {
            changes.add(
                    "Probable root cause changed."
            );
        }

        if (!Objects.equals(
                previous.confidence(),
                refreshed.confidence()
        )) {
            changes.add(
                    "Confidence changed from "
                            + previous.confidence()
                            + " to "
                            + refreshed.confidence()
                            + "."
            );
        }

        if (!Objects.equals(
                previous.affectedApplications(),
                refreshed.affectedApplications()
        )) {
            changes.add(
                    "Affected application list changed."
            );
        }

        if (!Objects.equals(
                previous.recommendedActions(),
                refreshed.recommendedActions()
        )) {
            changes.add(
                    "Recommended actions changed."
            );
        }

        if (!Objects.equals(
                previous.supportingEvidence(),
                refreshed.supportingEvidence()
        )) {
            changes.add(
                    "Supporting evidence changed."
            );
        }

        if (!Objects.equals(
                previous.classification(),
                refreshed.classification()
        )) {
            changes.add(
                    "Classification changed from "
                            + previous.classification()
                            + " to "
                            + refreshed.classification()
                            + "."
            );
        }

        if (changes.isEmpty()) {
            changes.add(
                    "No material change was detected."
            );
        }

        return changes;
    }
}

package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayComponentCatalogItem;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintInput;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintResult;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsRequest;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayEnvironmentComponentHintEntity;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayEnvironmentComponentHintRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReplayEnvironmentComponentHintService {

    private final ReplayCaseRepository caseRepository;
    private final ReplayEnvironmentComponentHintRepository hintRepository;
    private final ReplayComponentCatalogService catalogService;

    public ReplayEnvironmentComponentHintService(
            ReplayCaseRepository caseRepository,
            ReplayEnvironmentComponentHintRepository hintRepository,
            ReplayComponentCatalogService catalogService
    ) {
        this.caseRepository = caseRepository;
        this.hintRepository = hintRepository;
        this.catalogService = catalogService;
    }

    @Transactional
    public ReplayEnvironmentComponentHintsResponse addHints(
            UUID caseId,
            ReplayEnvironmentComponentHintsRequest request
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        ReplayEnvironmentComponentHintsRequest safeRequest =
                request == null
                        ? new ReplayEnvironmentComponentHintsRequest(List.of(), "")
                        : request;
        List<ReplayEnvironmentComponentHintResult> accepted = new ArrayList<>();
        List<ReplayEnvironmentComponentHintResult> rejected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ReplayEnvironmentComponentHintInput hint
                : safeRequest.componentHints()) {
            String componentKey = catalogService.normalizeKey(
                    hint == null ? "" : hint.componentKey()
            );
            String mode = catalogService.normalizeMode(
                    hint == null ? "" : hint.requestedMode()
            );
            String reason = hint == null ? "" : safeText(hint.reason());
            Optional<ReplayComponentCatalogItem> component =
                    catalogService.find(componentKey);
            if (component.isEmpty()) {
                rejected.add(new ReplayEnvironmentComponentHintResult(
                        componentKey,
                        mode,
                        reason,
                        "UNKNOWN_COMPONENT_KEY"
                ));
                continue;
            }
            if (!catalogService.modeAllowed(component.get(), mode)) {
                rejected.add(new ReplayEnvironmentComponentHintResult(
                        componentKey,
                        mode,
                        reason,
                        "REQUESTED_MODE_NOT_ALLOWED"
                ));
                continue;
            }
            ReplayEnvironmentComponentHintEntity entity =
                    new ReplayEnvironmentComponentHintEntity();
            entity.setCaseId(replayCase.getId());
            entity.setJiraKey(replayCase.getJiraKey());
            entity.setTargetKey(replayCase.getTargetKey());
            entity.setComponentKey(componentKey);
            entity.setRequestedMode(mode);
            entity.setReason(reason);
            entity.setNotes(safeText(safeRequest.notes()));
            hintRepository.save(entity);
            accepted.add(new ReplayEnvironmentComponentHintResult(
                    componentKey,
                    mode,
                    reason,
                    "ACCEPTED"
            ));
        }
        if (accepted.isEmpty() && rejected.isEmpty()) {
            warnings.add("NO_COMPONENT_HINTS_SUBMITTED");
        }
        return new ReplayEnvironmentComponentHintsResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                rejected.isEmpty() ? "ACCEPTED" : accepted.isEmpty()
                        ? "REJECTED"
                        : "PARTIAL",
                accepted,
                rejected,
                warnings
        );
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization", "[redacted-marker]")
                .replaceAll("(?i)cookie", "[redacted-marker]")
                .replaceAll("(?i)password", "[redacted-marker]")
                .replaceAll("(?i)token", "[redacted-marker]");
    }
}

package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.AuditEventEntity;
import com.etiya.replayfix.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(UUID caseId, String action, String details) {
        AuditEventEntity event = new AuditEventEntity();
        event.setCaseId(caseId);
        event.setAction(action);
        event.setActor("replayfix-platform");
        event.setDetails(details);
        repository.save(event);
    }

    public void record(UUID caseId, String action, String actor, String details) {
        String normalizedActor =
                actor == null || actor.isBlank()
                        ? "unknown"
                        : actor.trim();

        String normalizedDetails =
                details == null
                        ? ""
                        : details;

        record(
                caseId,
                action,
                "actor=" + normalizedActor + ", " + normalizedDetails
        );
    }
}

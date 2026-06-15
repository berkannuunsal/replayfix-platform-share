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
}

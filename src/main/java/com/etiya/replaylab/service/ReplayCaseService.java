package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CreateCaseRequest;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReplayCaseService {
    private final ReplayCaseRepository repository;
    private final ReplayLabProperties properties;
    private final AuditService auditService;

    public ReplayCaseService(
            ReplayCaseRepository repository,
            ReplayLabProperties properties,
            AuditService auditService
    ) {
        this.repository = repository;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public ReplayCaseEntity create(CreateCaseRequest request) {
        if (!properties.getTargets().containsKey(request.targetKey())) {
            throw new IllegalArgumentException(
                    "Unknown targetKey: " + request.targetKey()
            );
        }

        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setJiraKey(request.jiraKey());
        entity.setTargetKey(request.targetKey());
        entity.setOrderId(request.orderId());
        entity.setTraceId(request.traceId());
        entity.setIncidentTime(request.incidentTime());
        entity.setSourceBranch(request.sourceBranch());
        entity.setSourceCommit(request.sourceCommit());
        entity.setImageTag(request.imageTag());
        entity.setStatus(ReplayCaseStatus.NEW);

        ReplayCaseEntity saved = repository.save(entity);
        auditService.record(
                saved.getId(),
                "CASE_CREATED",
                "Created from Jira " + saved.getJiraKey()
        );
        return saved;
    }

    public ReplayCaseEntity get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Replay case not found: " + id
                ));
    }

    public List<ReplayCaseEntity> list() {
        return repository.findAll();
    }

    @Transactional
    public ReplayCaseEntity updateStatus(
            UUID id,
            ReplayCaseStatus status,
            String error
    ) {
        ReplayCaseEntity entity = get(id);
        entity.setStatus(status);
        entity.setLastError(error);
        ReplayCaseEntity saved = repository.save(entity);

        auditService.record(
                id,
                "STATUS_CHANGED",
                status.name() + (error == null ? "" : ": " + error)
        );
        return saved;
    }

    @Transactional
    public ReplayCaseEntity updateNamespace(UUID id, String namespace) {
        ReplayCaseEntity entity = get(id);
        entity.setNamespace(namespace);
        return repository.save(entity);
    }

    @Transactional
    public ReplayCaseEntity updateBranch(UUID id, String branch) {
        ReplayCaseEntity entity = get(id);
        entity.setGeneratedBranch(branch);
        return repository.save(entity);
    }

    @Transactional
    public ReplayCaseEntity updatePullRequest(UUID id, String url) {
        ReplayCaseEntity entity = get(id);
        entity.setPullRequestUrl(url);
        return repository.save(entity);
    }
}

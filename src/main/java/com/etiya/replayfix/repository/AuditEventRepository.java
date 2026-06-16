package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    List<AuditEventEntity> findByCaseIdOrderByCreatedAtDesc(UUID caseId);
    
    void deleteByCaseId(UUID caseId);
}

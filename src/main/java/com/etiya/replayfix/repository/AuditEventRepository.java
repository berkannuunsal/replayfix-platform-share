package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
}

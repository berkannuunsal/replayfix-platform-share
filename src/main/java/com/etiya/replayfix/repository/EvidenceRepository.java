package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.EvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EvidenceRepository extends JpaRepository<EvidenceEntity, UUID> {
    List<EvidenceEntity> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}

package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceRepository extends JpaRepository<EvidenceEntity, UUID> {
    List<EvidenceEntity> findByCaseId(UUID caseId);
    
    List<EvidenceEntity> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
    
    List<EvidenceEntity> findByCaseIdAndEvidenceType(UUID caseId, EvidenceType evidenceType);
    
    List<EvidenceEntity> findByCaseIdAndEvidenceTypeAndSource(UUID caseId, EvidenceType evidenceType, String source);
    
    void deleteByCaseId(UUID caseId);
}

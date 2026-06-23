package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.CodeChangeAdvisoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeChangeAdvisoryRepository
        extends JpaRepository<CodeChangeAdvisoryEntity, UUID> {

    List<CodeChangeAdvisoryEntity> findByCaseIdOrderByCreatedAtDesc(
            UUID caseId
    );

    Optional<CodeChangeAdvisoryEntity>
            findFirstByCaseIdAndAdvisoryModeOrderByCreatedAtDesc(
            UUID caseId,
            String advisoryMode
    );
}

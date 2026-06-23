package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.ReplayInputEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReplayInputRepository
        extends JpaRepository<ReplayInputEntity, UUID> {

    List<ReplayInputEntity> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    Optional<ReplayInputEntity> findFirstByCaseIdOrderByCreatedAtDesc(
            UUID caseId
    );
}

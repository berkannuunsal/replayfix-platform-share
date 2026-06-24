package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.ReplayEnvironmentComponentHintEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReplayEnvironmentComponentHintRepository
        extends JpaRepository<ReplayEnvironmentComponentHintEntity, UUID> {

    List<ReplayEnvironmentComponentHintEntity> findByCaseIdOrderByCreatedAtDesc(
            UUID caseId
    );
}

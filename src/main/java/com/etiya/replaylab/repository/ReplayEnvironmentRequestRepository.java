package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.ReplayEnvironmentRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReplayEnvironmentRequestRepository
        extends JpaRepository<ReplayEnvironmentRequestEntity, UUID> {

    List<ReplayEnvironmentRequestEntity> findByCaseIdOrderByCreatedAtDesc(
            UUID caseId
    );
}

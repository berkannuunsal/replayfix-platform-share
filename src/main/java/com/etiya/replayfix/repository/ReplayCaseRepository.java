package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReplayCaseRepository extends JpaRepository<ReplayCaseEntity, UUID> {

    Optional<ReplayCaseEntity> findFirstByJiraKey(String jiraKey);
}

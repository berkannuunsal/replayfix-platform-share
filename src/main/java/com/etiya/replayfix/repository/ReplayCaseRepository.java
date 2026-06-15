package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ReplayCaseRepository extends JpaRepository<ReplayCaseEntity, UUID> {
}

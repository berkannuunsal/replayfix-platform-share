package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.JiraCommentPublicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JiraCommentPublicationRepository extends JpaRepository<JiraCommentPublicationEntity, UUID> {

    Optional<JiraCommentPublicationEntity> findByIssueKeyAndContentSha256(String issueKey, String contentSha256);

    List<JiraCommentPublicationEntity> findByCaseIdOrderByRequestedAtDesc(UUID caseId);

    List<JiraCommentPublicationEntity> findByStatusAndRequestedAtBefore(String status, java.time.Instant before);
}

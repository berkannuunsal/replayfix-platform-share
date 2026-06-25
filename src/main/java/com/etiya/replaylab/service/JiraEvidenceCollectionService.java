package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.repository.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JiraEvidenceCollectionService {

    private static final Logger log = LoggerFactory.getLogger(JiraEvidenceCollectionService.class);

    private final EvidenceRepository evidenceRepository;

    public JiraEvidenceCollectionService(EvidenceRepository evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    @Transactional
    public EvidenceEntity collectJiraEvidence(UUID caseId, String issueKey) {
        log.info("Collecting Jira evidence for case {} and issue {}", caseId, issueKey);

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        evidence.setSource("jira-issue");
        evidence.setContentText("Jira issue data for " + issueKey);
        evidence.setContentLocation("https://jira.example.com/browse/" + issueKey);
        evidence.setSanitized(true);

        return evidenceRepository.save(evidence);
    }
}

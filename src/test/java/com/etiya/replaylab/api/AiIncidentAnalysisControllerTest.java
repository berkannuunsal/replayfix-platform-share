package com.etiya.replaylab.api;

import com.etiya.replaylab.domain.*;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "replaylab.ai.enabled=true",
        "replaylab.ai.provider=MOCK",
        "replaylab.ai.model=mock-replaylab-v1"
})
@Transactional
class AiIncidentAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReplayCaseRepository caseRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Test
    void testAnalyze_CaseNotFound_Returns404() throws Exception {
        UUID nonExistentCaseId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/cases/" + nonExistentCaseId + "/analyze-ai"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void testAnalyze_InsufficientEvidence_Returns422() throws Exception {
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-001");
        caseEntity.setTargetKey("test-target");
        caseEntity = caseRepository.save(caseEntity);

        // No evidence at all
        mockMvc.perform(post("/api/v1/cases/" + caseEntity.getId() + "/analyze-ai"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void testAnalyze_Success() throws Exception {
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-002");
        caseEntity.setTargetKey("test-target");
        caseEntity = caseRepository.save(caseEntity);

        // Add Jira evidence
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseEntity.getId());
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-test");
        jiraEvidence.setContentText("Test issue");
        evidenceRepository.save(jiraEvidence);

        // Add Loki evidence
        EvidenceEntity lokiEvidence = new EvidenceEntity();
        lokiEvidence.setCaseId(caseEntity.getId());
        lokiEvidence.setEvidenceType(EvidenceType.LOKI_LOGS);
        lokiEvidence.setSource("loki-test");
        lokiEvidence.setContentText("Test logs");
        evidenceRepository.save(lokiEvidence);

        mockMvc.perform(post("/api/v1/cases/" + caseEntity.getId() + "/analyze-ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseEntity.getId().toString()))
                .andExpect(jsonPath("$.analysisType").value("MOCK_AI_ANALYSIS"))
                .andExpect(jsonPath("$.synthetic").value(true))
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andExpect(jsonPath("$.confidence").exists());

        // Verify AI evidence was persisted
        var savedEvidence = evidenceRepository.findByCaseId(caseEntity.getId());
        assertThat(savedEvidence).anyMatch(e -> 
                e.getEvidenceType() == EvidenceType.AI_ROOT_CAUSE &&
                e.getSource().contains("ai-provider-mock")
        );
    }
}

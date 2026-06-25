package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.model.SourceCandidateFlowChainItem;
import com.etiya.replaylab.model.SourceCandidateMethod;
import com.etiya.replaylab.model.SourceFlowAnchor;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceReasoningContextBuilderTest {

    @Test
    void buildsSanitizedSourceReasoningContext() throws Exception {
        UUID caseId = UUID.randomUUID();
        EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.ROVO_RCA
        )).thenReturn(List.of(evidence(caseId,
                "{\"token\":\"secret-token\",\"summary\":\"safe\"}")));

        SourceReasoningContextBuilder builder =
                new SourceReasoningContextBuilder(
                        evidenceRepository,
                        new EvidenceSanitizer(),
                        new ObjectMapper().findAndRegisterModules()
                );

        var result = builder.build(
                caseEntity(caseId),
                "DCE/backend",
                "test2",
                List.of(new SourceFlowAnchor(
                        "/businessFlow/initialize",
                        "ENDPOINT",
                        "test"
                )),
                List.of(new SourceCandidateFlowChainItem(
                        "CONTROLLER",
                        "src/RegionController.java",
                        "RegionController",
                        "initialize",
                        List.of("/businessFlow/initialize"),
                        "endpoint",
                        "HYPOTHESIS"
                )),
                List.of(new SourceCandidateMethod(
                        "src/RegionController.java",
                        "RegionController",
                        "initialize",
                        1,
                        5,
                        List.of("/businessFlow/initialize"),
                        "String password = \"secret\";"
                )),
                List.of(),
                List.of()
        );

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(result.context());

        assertThat(json).contains("[REDACTED]");
        assertThat(json).doesNotContain("secret-token");
        assertThat(json).doesNotContain("password = \"secret\"");
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        return replayCase;
    }

    private EvidenceEntity evidence(UUID caseId, String content) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.ROVO_RCA);
        evidence.setSource("test");
        evidence.setContentText(content);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}

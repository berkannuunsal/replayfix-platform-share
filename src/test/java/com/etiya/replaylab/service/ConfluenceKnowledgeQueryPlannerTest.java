package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.ConfluenceKnowledgeQueryPlan;
import com.etiya.replaylab.model.ConfluencePlannedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceKnowledgeQueryPlannerTest {

    private ConfluenceKnowledgeQueryPlanner planner;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReplayLabProperties();
        var confluence = new ReplayLabProperties.ConfluenceEndpoint();
        confluence.setMaxSearchResults(15);
        confluence.setAllowedSpaceKeys("");
        properties.getIntegrations().setConfluence(confluence);

        planner = new ConfluenceKnowledgeQueryPlanner(properties);
    }

    @Test
    void testJiraKeyQuery() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"FIZZMS-8346\",\"summary\":\"Order completion failed\"}");
        evidence.add(jiraEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertEquals(caseId, plan.caseId());
        assertFalse(plan.queries().isEmpty());
        assertTrue(plan.queries().stream()
                .anyMatch(q -> q.cql().contains("FIZZMS-8346")));
    }

    @Test
    void testErrorCodeQuery() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity logEvidence = new EvidenceEntity();
        logEvidence.setCaseId(caseId);
        logEvidence.setEvidenceType(EvidenceType.LOKI_LOG);
        logEvidence.setSource("loki-log");
        logEvidence.setContentText("Error 401 Unauthorized occurred during authentication");
        evidence.add(logEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertFalse(plan.errorTerms().isEmpty());
        assertTrue(plan.errorTerms().contains("401"));
    }

    @Test
    void testEndpointQuery() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity endpointEvidence = new EvidenceEntity();
        endpointEvidence.setCaseId(caseId);
        endpointEvidence.setEvidenceType(EvidenceType.LOKI_LOG);
        endpointEvidence.setSource("loki-log");
        endpointEvidence.setContentText("POST /api/orders failed with status 500");
        evidence.add(endpointEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertFalse(plan.endpointTerms().isEmpty());
    }

    @Test
    void testServiceRepositoryQuery() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity repoEvidence = new EvidenceEntity();
        repoEvidence.setCaseId(caseId);
        repoEvidence.setEvidenceType(EvidenceType.REPLAY_OUTPUT);
        repoEvidence.setSource("repository-resolution");
        repoEvidence.setContentText("{\"repository\":\"bss-backend\",\"project\":\"BAR\"}");
        evidence.add(repoEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertFalse(plan.serviceTerms().isEmpty());
    }

    @Test
    void testSpaceAllowlist() {
        properties.getIntegrations().getConfluence().setAllowedSpaceKeys("TECH,OPS,DEV");

        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST-123\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertEquals(3, plan.allowedSpaceKeys().size());
        assertTrue(plan.allowedSpaceKeys().contains("TECH"));
        assertTrue(plan.queries().stream()
                .anyMatch(q -> q.cql().contains("space in")));
    }

    @Test
    void testQuoteEscape() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST-123\",\"summary\":\"Quote \\\"test\\\" string\"}");
        evidence.add(jiraEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertTrue(plan.queries().stream()
                .noneMatch(q -> q.cql().contains("\"test\"")));
    }

    @Test
    void testRawCqlInjectionPrevention() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity maliciousEvidence = new EvidenceEntity();
        maliciousEvidence.setCaseId(caseId);
        maliciousEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        maliciousEvidence.setSource("jira-issue");
        maliciousEvidence.setContentText("{\"key\":\"TEST\",\"summary\":\"creator=admin\"}");
        evidence.add(maliciousEvidence);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertTrue(plan.queries().stream()
                .noneMatch(q -> q.cql().toLowerCase().contains("creator=")));
    }

    @Test
    void testMaxQueryLimit() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            EvidenceEntity ev = new EvidenceEntity();
            ev.setCaseId(caseId);
            ev.setEvidenceType(EvidenceType.JIRA_ISSUE);
            ev.setSource("jira-issue");
            ev.setContentText("{\"key\":\"TEST-" + i + "\",\"summary\":\"Test " + i + "\"}");
            evidence.add(ev);
        }

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        assertTrue(plan.queries().size() <= 10, "Should not exceed max query limit");
    }

    @Test
    void testDuplicateTermCleaning() {
        UUID caseId = UUID.randomUUID();
        List<EvidenceEntity> evidence = new ArrayList<>();

        EvidenceEntity ev1 = new EvidenceEntity();
        ev1.setCaseId(caseId);
        ev1.setEvidenceType(EvidenceType.JIRA_ISSUE);
        ev1.setSource("jira-issue");
        ev1.setContentText("{\"key\":\"TEST-123\",\"summary\":\"bss-backend error\"}");
        evidence.add(ev1);

        EvidenceEntity ev2 = new EvidenceEntity();
        ev2.setCaseId(caseId);
        ev2.setEvidenceType(EvidenceType.LOKI_LOG);
        ev2.setSource("loki-log");
        ev2.setContentText("bss-backend service failed");
        evidence.add(ev2);

        ConfluenceKnowledgeQueryPlan plan = planner.plan(caseId, evidence);

        assertNotNull(plan);
        long backendCount = plan.serviceTerms().stream()
                .filter(t -> t.contains("backend"))
                .count();
        assertTrue(backendCount <= 1, "Duplicate terms should be cleaned");
    }
}

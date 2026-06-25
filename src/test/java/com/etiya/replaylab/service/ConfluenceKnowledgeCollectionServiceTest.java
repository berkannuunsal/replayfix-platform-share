package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.integration.ConfluenceClient;
import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfluenceKnowledgeCollectionServiceTest {

    private ConfluenceKnowledgeCollectionService service;
    private ConfluenceClient confluenceClient;
    private EvidenceService evidenceService;
    private AuditService auditService;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        confluenceClient = mock(ConfluenceClient.class);
        evidenceService = mock(EvidenceService.class);
        auditService = mock(AuditService.class);

        properties = new ReplayLabProperties();
        var confluence = new ReplayLabProperties.ConfluenceEndpoint();
        confluence.setMaxSearchResults(15);
        confluence.setMaxPagesPerCase(5);
        confluence.setMaxTotalChars(80000);
        confluence.setMaxPageChars(30000);
        properties.getIntegrations().setConfluence(confluence);

        var sanitizer = new EvidenceSanitizer();
        var queryPlanner = new ConfluenceKnowledgeQueryPlanner(properties);
        var ranker = new ConfluenceKnowledgeRanker();
        var textExtractor = new ConfluencePageTextExtractor(properties, sanitizer);

        service = new ConfluenceKnowledgeCollectionService(
                confluenceClient,
                queryPlanner,
                ranker,
                textExtractor,
                evidenceService,
                auditService,
                properties,
                new ObjectMapper()
        );
    }

    @Test
    void testMultiQuerySearch() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST-123\",\"summary\":\"Test issue\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page AND text~\"TEST-123\"",
                1,
                List.of(new ConfluenceSearchHit(
                        "1",
                        "Test Page",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Test content",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        ConfluencePageDocument pageDoc = new ConfluencePageDocument(
                "1",
                "Test Page",
                "space1",
                "TECH",
                "current",
                "http://test/1",
                1,
                "2024-01-01",
                "storage",
                "Plain text content",
                20,
                false,
                List.of(),
                List.of()
        );

        when(confluenceClient.getPage("1")).thenReturn(pageDoc);

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
        assertTrue(context.searchedQueryCount() > 0);
        verify(confluenceClient, atLeastOnce()).search(any());
        verify(evidenceService, atLeastOnce()).save(any(), any(), any(), any(), anyBoolean());
        verify(auditService, times(1)).record(eq(caseId), eq("CONFLUENCE_KNOWLEDGE_COLLECTED"), any(), any());
    }

    @Test
    void testPartialSuccess() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST-123\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                2,
                List.of(
                        new ConfluenceSearchHit("1", "Page1", "TECH", "Tech", "http://1", "", "2024-01-01", 1, 1.0, 0, List.of()),
                        new ConfluenceSearchHit("2", "Page2", "TECH", "Tech", "http://2", "", "2024-01-01", 1, 1.0, 0, List.of())
                ),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        when(confluenceClient.getPage("1")).thenReturn(new ConfluencePageDocument(
                "1", "Page1", "space1", "TECH", "current", "http://1",
                1, "2024-01-01", "storage", "Content 1", 10, false, List.of(), List.of()
        ));

        when(confluenceClient.getPage("2")).thenThrow(new RuntimeException("404"));

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
        assertTrue(context.selectedPageCount() > 0);
        assertFalse(context.warnings().isEmpty());
    }

    @Test
    void testRanking() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST-123\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                2,
                List.of(
                        new ConfluenceSearchHit("1", "Low Priority", "TECH", "Tech", "http://1", "", "2024-01-01", 1, 1.0, 0, List.of()),
                        new ConfluenceSearchHit("2", "TEST-123 High Priority", "TECH", "Tech", "http://2", "", "2024-01-01", 1, 1.0, 0, List.of())
                ),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        when(confluenceClient.getPage(anyString())).thenReturn(new ConfluencePageDocument(
                "1", "Page", "space1", "TECH", "current", "http://1",
                1, "2024-01-01", "storage", "Content", 10, false, List.of(), List.of()
        ));

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
    }

    @Test
    void testMaxSelectedPageLimit() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        List<ConfluenceSearchHit> manyHits = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manyHits.add(new ConfluenceSearchHit(
                    String.valueOf(i),
                    "Page " + i,
                    "TECH",
                    "Tech",
                    "http://" + i,
                    "",
                    "2024-01-01",
                    1,
                    1.0,
                    0,
                    List.of()
            ));
        }

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                20,
                manyHits,
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        when(confluenceClient.getPage(anyString())).thenReturn(new ConfluencePageDocument(
                "1", "Page", "space1", "TECH", "current", "http://1",
                1, "2024-01-01", "storage", "Content", 10, false, List.of(), List.of()
        ));

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
        assertTrue(context.selectedPageCount() <= 5);
    }

    @Test
    void testTotalCharLimit() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                1,
                List.of(new ConfluenceSearchHit("1", "Page", "TECH", "Tech", "http://1", "", "2024-01-01", 1, 1.0, 0, List.of())),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        String longContent = "x".repeat(100000);
        when(confluenceClient.getPage("1")).thenReturn(new ConfluencePageDocument(
                "1", "Page", "space1", "TECH", "current", "http://1",
                1, "2024-01-01", "storage", longContent, longContent.length(), false, List.of(), List.of()
        ));

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
        assertTrue(context.totalIncludedChars() <= 80000);
    }

    @Test
    void testEvidenceSaving() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                0,
                List.of(),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);

        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(evidenceService, atLeastOnce()).save(
                eq(caseId),
                eq(EvidenceType.REPLAY_OUTPUT),
                sourceCaptor.capture(),
                any(),
                eq(true)
        );

        List<String> sources = sourceCaptor.getAllValues();
        assertTrue(sources.contains("confluence-knowledge-query-plan"));
        assertTrue(sources.contains("confluence-knowledge-context"));
    }

    @Test
    void testAuditLogging() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                0,
                List.of(),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        service.collect(caseId);

        verify(auditService, times(1)).record(
                eq(caseId),
                eq("CONFLUENCE_KNOWLEDGE_COLLECTED"),
                any(),
                any()
        );
    }

    @Test
    void test403PageDoesNotFailCollection() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = new ArrayList<>();
        EvidenceEntity jiraEvidence = new EvidenceEntity();
        jiraEvidence.setCaseId(caseId);
        jiraEvidence.setEvidenceType(EvidenceType.JIRA_ISSUE);
        jiraEvidence.setSource("jira-issue");
        jiraEvidence.setContentText("{\"key\":\"TEST\",\"summary\":\"Test\"}");
        evidence.add(jiraEvidence);

        when(evidenceService.list(caseId)).thenReturn(evidence);

        ConfluenceSearchResponse searchResponse = new ConfluenceSearchResponse(
                "type=page",
                2,
                List.of(
                        new ConfluenceSearchHit("1", "Page1", "TECH", "Tech", "http://1", "", "2024-01-01", 1, 1.0, 0, List.of()),
                        new ConfluenceSearchHit("2", "Page2", "TECH", "Tech", "http://2", "", "2024-01-01", 1, 1.0, 0, List.of())
                ),
                null,
                List.of()
        );

        when(confluenceClient.search(any())).thenReturn(searchResponse);

        when(confluenceClient.getPage("1")).thenReturn(new ConfluencePageDocument(
                "1", "Page1", "space1", "TECH", "current", "http://1",
                1, "2024-01-01", "storage", "Content", 10, false, List.of(), List.of("403 Forbidden")
        ));

        when(confluenceClient.getPage("2")).thenReturn(new ConfluencePageDocument(
                "2", "Page2", "space1", "TECH", "current", "http://2",
                1, "2024-01-01", "storage", "Content", 10, false, List.of(), List.of()
        ));

        ConfluenceKnowledgeContext context = service.collect(caseId);

        assertNotNull(context);
        assertTrue(context.selectedPageCount() > 0);
    }
}

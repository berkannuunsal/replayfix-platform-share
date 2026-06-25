package com.etiya.replaylab.service;

import com.etiya.replaylab.model.ConfluenceKnowledgeQueryPlan;
import com.etiya.replaylab.model.ConfluenceSearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceKnowledgeRankerTest {

    private ConfluenceKnowledgeRanker ranker;

    @BeforeEach
    void setUp() {
        ranker = new ConfluenceKnowledgeRanker();
    }

    @Test
    void testJiraKeyTitleMatch() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "FIZZMS-8346 Order Issue",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Details about FIZZMS-8346",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of("FIZZMS-8346"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() > 100);
        assertFalse(ranked.get(0).matchReasons().isEmpty());
    }

    @Test
    void testExactEndpointMatch() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "API /api/orders Documentation",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Endpoint details for /api/orders",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of(),
                List.of(),
                List.of(),
                List.of("/api/orders"),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() >= 100);
    }

    @Test
    void testErrorCodeMatch() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "401 Unauthorized Error Guide",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "How to fix 401 errors",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of(),
                List.of(),
                List.of("401"),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() >= 100);
    }

    @Test
    void testApplicationMatch() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "bss-backend Service Documentation",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Technical documentation for bss-backend",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of(),
                List.of("bss-backend"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() >= 80);
    }

    @Test
    void testRecentPageBonus() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "Recent Documentation",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Updated recently",
                        "2025-12-01T00:00:00Z",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of("Documentation"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() > 0);
    }

    @Test
    void testArchivedPenalty() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "Archived Old Documentation",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "This is archived content",
                        "2020-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of("Documentation"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).replayLabScore() >= 0);
    }

    @Test
    void testDuplicatePageRemoval() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
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
                ),
                new ConfluenceSearchHit(
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
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of("Test"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertEquals(1, ranked.size(), "Duplicate pages should be removed");
    }

    @Test
    void testScoreOrdering() {
        List<ConfluenceSearchHit> hits = List.of(
                new ConfluenceSearchHit(
                        "1",
                        "Low Priority",
                        "TECH",
                        "Technical",
                        "http://test/1",
                        "Content",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                ),
                new ConfluenceSearchHit(
                        "2",
                        "High Priority FIZZMS-123",
                        "TECH",
                        "Technical",
                        "http://test/2",
                        "Important content with FIZZMS-123",
                        "2024-01-01",
                        1,
                        1.0,
                        0,
                        List.of()
                )
        );

        ConfluenceKnowledgeQueryPlan plan = new ConfluenceKnowledgeQueryPlan(
                UUID.randomUUID(),
                List.of("FIZZMS-123"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<ConfluenceSearchHit> ranked = ranker.rank(hits, plan);

        assertEquals(2, ranked.size());
        assertEquals("2", ranked.get(0).pageId());
        assertTrue(ranked.get(0).replayLabScore() > ranked.get(1).replayLabScore());
    }
}

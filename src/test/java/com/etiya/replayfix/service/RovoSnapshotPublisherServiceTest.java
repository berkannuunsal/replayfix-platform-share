package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.JiraCommentPublishResponse;
import com.etiya.replayfix.model.ReplayFixEvidenceSnapshot;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RovoSnapshotPublisherServiceTest {

    private RovoSnapshotPublisherService service;
    private ReplayCaseRepository caseRepository;
    private EvidenceSnapshotService snapshotService;
    private JiraClient jiraClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        snapshotService = mock(EvidenceSnapshotService.class);
        jiraClient = mock(JiraClient.class);
        objectMapper = new ObjectMapper();

        service = new RovoSnapshotPublisherService(
                caseRepository,
                snapshotService,
                jiraClient,
                objectMapper
        );
    }

    @Test
    void shouldNotContainLegacyWikiMarkup() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();
        String adfString = objectMapper.writeValueAsString(adf);

        // Verify no legacy wiki markup
        assertFalse(adfString.contains("h3."), "Should not contain h3. wiki markup");
        assertFalse(adfString.contains("h2."), "Should not contain h2. wiki markup");
        assertFalse(adfString.contains("h1."), "Should not contain h1. wiki markup");
        assertFalse(adfString.contains("{panel"), "Should not contain {panel} wiki markup");
        assertFalse(adfString.contains("{noformat}"), "Should not contain {noformat} wiki markup");
        assertFalse(adfString.contains("{code}"), "Should not contain {code} wiki markup");
    }

    @Test
    void shouldContainSnapshotMarkers() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();
        String adfString = objectMapper.writeValueAsString(adf);

        // Verify markers are present
        assertTrue(adfString.contains("REPLAYFIX_EVIDENCE_SNAPSHOT_V1"), 
                "Should contain start marker");
        assertTrue(adfString.contains("REPLAYFIX_EVIDENCE_SNAPSHOT_END"), 
                "Should contain end marker");
    }

    @Test
    void shouldHaveExtractableAndParsableJson() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();
        String adfString = objectMapper.writeValueAsString(adf);

        // Extract JSON block
        Pattern pattern = Pattern.compile(
                "REPLAYFIX_EVIDENCE_SNAPSHOT_V1\\\\n(.+?)\\\\nREPLAYFIX_EVIDENCE_SNAPSHOT_END",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(adfString);

        assertTrue(matcher.find(), "Should be able to extract JSON block");

        String extractedJson = matcher.group(1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");

        // Verify JSON is parsable
        assertDoesNotThrow(() -> {
            ReplayFixEvidenceSnapshot parsed = objectMapper.readValue(extractedJson, ReplayFixEvidenceSnapshot.class);
            assertNotNull(parsed);
            assertEquals(snapshot.jiraKey(), parsed.jiraKey());
        }, "Extracted JSON should be parsable");
    }

    @Test
    void shouldNotContainSecrets() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();
        String adfString = objectMapper.writeValueAsString(adf).toLowerCase();

        // Verify no secrets
        assertFalse(adfString.contains("password"), "Should not contain passwords");
        assertFalse(adfString.contains("secret"), "Should not contain secrets");
        assertFalse(adfString.contains("token"), "Should not contain tokens");
        assertFalse(adfString.contains("authorization:"), "Should not contain authorization headers");
        assertFalse(adfString.contains("cookie:"), "Should not contain cookies");
        assertFalse(adfString.contains("bearer "), "Should not contain bearer tokens");
    }

    @Test
    void shouldUseModernAdfFormat() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();

        // Verify ADF structure
        assertEquals(1, adf.get("version").asInt());
        assertEquals("doc", adf.get("type").asText());
        assertTrue(adf.has("content"));
        assertTrue(adf.get("content").isArray());

        // Verify has heading
        JsonNode content = adf.get("content");
        boolean hasHeading = false;
        for (JsonNode node : content) {
            if ("heading".equals(node.get("type").asText())) {
                hasHeading = true;
                break;
            }
        }
        assertTrue(hasHeading, "Should have heading in ADF");
    }

    @Test
    void shouldUseTurkishLabels() throws Exception {
        // Given
        UUID caseId = UUID.randomUUID();
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("TEST-123");

        ReplayFixEvidenceSnapshot snapshot = createTestSnapshot();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
        when(snapshotService.buildSnapshot(caseId)).thenReturn(snapshot);
        when(jiraClient.addCommentAdf(eq("TEST-123"), any(JsonNode.class)))
                .thenReturn(new JiraCommentPublishResponse(true, 201, "TEST-123", "10001", "url", "time", List.of()));

        // When
        service.publishToJira(caseId);

        // Then
        ArgumentCaptor<JsonNode> adfCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(jiraClient).addCommentAdf(eq("TEST-123"), adfCaptor.capture());

        JsonNode adf = adfCaptor.getValue();
        String adfString = objectMapper.writeValueAsString(adf);

        // Verify Turkish labels
        assertTrue(adfString.contains("Vaka"), "Should contain Turkish label 'Vaka'");
        assertTrue(adfString.contains("Koruma Kuralları"), "Should contain Turkish label 'Koruma Kuralları'");
        assertTrue(adfString.contains("Deterministik RCA"), "Should contain Turkish label 'Deterministik RCA'");
    }

    private ReplayFixEvidenceSnapshot createTestSnapshot() {
        return new ReplayFixEvidenceSnapshot(
                "1.0",
                UUID.randomUUID(),
                "TEST-123",
                "TEST-123",
                false,
                null, // repository
                null, // jenkins
                null, // incidentVersion
                null, // runtime evidence
                null, // source context
                null, // deterministic RCA
                null, // evidenceIds
                new ReplayFixEvidenceSnapshot.GuardrailsInfo(
                        true, // evidenceOnly
                        true, // noAutomaticMerge
                        true, // noProductionDeployment
                        true  // humanApprovalRequired
                )
        );
    }
}

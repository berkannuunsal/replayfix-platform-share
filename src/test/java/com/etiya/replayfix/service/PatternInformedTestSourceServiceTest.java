package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatternInformedTestSourceServiceTest {

    private PatternInformedTestSourceService service;
    private EvidenceService evidenceService;
    private JavaSourceSignatureAnalyzer signatureAnalyzer;
    private PatternInformedRegressionTestRenderer renderer;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        evidenceService = mock(EvidenceService.class);
        signatureAnalyzer = mock(JavaSourceSignatureAnalyzer.class);
        renderer = new PatternInformedRegressionTestRenderer();
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();

        service = new PatternInformedTestSourceService(
                evidenceService,
                signatureAnalyzer,
                renderer,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldReadAllRequiredEvidence() throws Exception {
        UUID caseId = UUID.randomUUID();

        setupMocksForGeneration(caseId);

        service.generate(caseId);

        verify(evidenceService, times(3)).list(caseId);
    }

    @Test
    void shouldSaveCandidateEvidence() throws Exception {
        UUID caseId = UUID.randomUUID();

        setupMocksForGeneration(caseId);

        service.generate(caseId);

        verify(evidenceService).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                eq("pattern-informed-test-source-candidate"),
                anyString(),
                eq(true)
        );
    }

    @Test
    void shouldRecordAuditEvent() throws Exception {
        UUID caseId = UUID.randomUUID();

        setupMocksForGeneration(caseId);

        service.generate(caseId);

        verify(auditService).record(
                eq(caseId),
                eq("PATTERN_INFORMED_TEST_SOURCE_CREATED"),
                eq("system"),
                anyString()
        );
    }

    @Test
    void shouldNotModifyExistingScaffold() throws Exception {
        UUID caseId = UUID.randomUUID();

        setupMocksForGeneration(caseId);

        PatternInformedTestSourceResult result = service.generate(caseId);

        assertFalse(result.candidate().fileWritten());
    }

    @Test
    void shouldNotCallGitOrJenkinsOperations() throws Exception {
        UUID caseId = UUID.randomUUID();

        setupMocksForGeneration(caseId);

        service.generate(caseId);

        // Verify no file writing or test execution
        assertFalse(service.generate(caseId).candidate().fileWritten());
        assertFalse(service.generate(caseId).candidate().testExecuted());
    }

    private void setupMocksForGeneration(UUID caseId) throws Exception {
        RegressionTestPlan plan = new RegressionTestPlan(
                caseId,
                "test-repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                "MyService",
                "doSomething",
                "MyServiceTest",
                "testDoSomething",
                "src/test/java/com/example/MyServiceTest.java",
                "Test scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "Expected failure",
                "Expected success",
                List.of(),
                0.8,
                "DETERMINISTIC",
                false,
                false,
                true,
                List.of()
        );

        EvidenceEntity planEvidence = new EvidenceEntity();
        planEvidence.setCaseId(caseId);
        planEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        planEvidence.setSource("regression-test-plan");
        planEvidence.setContentText(objectMapper.writeValueAsString(plan));

        TestPatternCandidate selectedPattern = new TestPatternCandidate(
                "src/test/java/com/example/ExampleTest.java",
                "com.example",
                "ExampleTest",
                "JUnit 5",
                "PLAIN_JUNIT",
                100,
                List.of(),
                List.of("org.junit.jupiter.api.Test"),
                List.of("@Test"),
                List.of("testMethod"),
                List.of(),
                ""
        );

        ExistingTestPatternSelection patternSelection = new ExistingTestPatternSelection(
                caseId,
                "test-repo",
                "workspace",
                "MyService",
                "doSomething",
                "com.example",
                1,
                1,
                selectedPattern,
                List.of(),
                List.of(),
                List.of()
        );

        EvidenceEntity patternEvidence = new EvidenceEntity();
        patternEvidence.setCaseId(caseId);
        patternEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        patternEvidence.setSource("existing-test-pattern-selection");
        patternEvidence.setContentText(objectMapper.writeValueAsString(patternSelection));

        String sourceContext = objectMapper.writeValueAsString(
                Map.of("excerpts", List.of("package com.example; public class MyService {}"))
        );

        EvidenceEntity sourceContextEvidence = new EvidenceEntity();
        sourceContextEvidence.setCaseId(caseId);
        sourceContextEvidence.setEvidenceType(EvidenceType.SOURCE_CONTEXT);
        sourceContextEvidence.setSource("jenkins-validated-source-context");
        sourceContextEvidence.setContentText(sourceContext);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, patternEvidence, sourceContextEvidence));

        JavaSourceSignatureAnalysis analysis = new JavaSourceSignatureAnalysis(
                "com.example",
                "MyService",
                new JavaConstructorSignature("MyService", List.of()),
                new JavaMethodSignature("doSomething", "String", List.of()),
                List.of(),
                List.of(),
                List.of()
        );

        when(signatureAnalyzer.analyze(anyString(), anyString(), anyString()))
                .thenReturn(analysis);
    }
}

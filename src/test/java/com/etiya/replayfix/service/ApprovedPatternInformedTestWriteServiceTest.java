package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.ApprovalStatus;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovedPatternInformedTestWriteServiceTest {

    private ApprovedPatternInformedTestWriteService service;
    private ReplayFixProperties properties;
    private EvidenceService evidenceService;
    private ApprovalService approvalService;
    private VersionedTestPathResolver pathResolver;
    private JavaTopLevelClassRenamer classRenamer;
    private AtomicWorkspaceFileWriter fileWriter;
    private GitWorkspaceService gitService;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        properties = mock(ReplayFixProperties.class);
        evidenceService = mock(EvidenceService.class);
        approvalService = mock(ApprovalService.class);
        pathResolver = new VersionedTestPathResolver();
        classRenamer = new JavaTopLevelClassRenamer();
        fileWriter = mock(AtomicWorkspaceFileWriter.class);
        gitService = mock(GitWorkspaceService.class);
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();

        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowGeneratedCodeWrite(true);
        policy.setPatternTestMinConfidence(0.65);
        when(properties.getPolicy()).thenReturn(policy);

        service = new ApprovedPatternInformedTestWriteService(
                properties,
                evidenceService,
                approvalService,
                pathResolver,
                classRenamer,
                fileWriter,
                gitService,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldThrowWhenPolicyDisablesWrite() {
        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowGeneratedCodeWrite(false);
        when(properties.getPolicy()).thenReturn(policy);

        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );

        verify(fileWriter, never()).writeNewFile(any(), any(), any());
    }

    @Test
    void shouldThrowWhenApprovalNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        setupMocksWithCandidate(caseId, candidateEvidenceId, true);

        when(approvalService.requireApprovedPatternInformedTestSource(
                caseId,
                candidateEvidenceId
        )).thenThrow(new IllegalStateException("Not found"));

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenApprovalIdMismatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID wrongApprovalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        setupMocksWithCandidate(caseId, candidateEvidenceId, true);

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(wrongApprovalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        when(approvalService.requireApprovedPatternInformedTestSource(
                caseId,
                candidateEvidenceId
        )).thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenCandidateHashMismatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        setupMocksWithCandidate(caseId, candidateEvidenceId, false);

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        when(approvalService.requireApprovedPatternInformedTestSource(
                caseId,
                candidateEvidenceId
        )).thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenReadinessNotReady() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        PatternInformedTestSourceCandidate candidate = createCandidate(
                caseId,
                TestSourceReadiness.NEEDS_MANUAL_COMPLETION
        );

        setupMocksWithSpecificCandidate(
                caseId,
                candidateEvidenceId,
                candidate
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenConfidenceBelowThreshold() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        PatternInformedTestSourceCandidate candidate = createCandidateWithConfidence(
                caseId,
                0.50
        );

        setupMocksWithSpecificCandidate(
                caseId,
                candidateEvidenceId,
                candidate
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenUnresolvedSymbolsExist() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        PatternInformedTestSourceCandidate candidate = createCandidateWithUnresolved(
                caseId,
                List.of("Unknown dependency")
        );

        setupMocksWithSpecificCandidate(
                caseId,
                candidateEvidenceId,
                candidate
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.write(caseId, approvalId)
        );
    }

    @Test
    void shouldWriteVersion2WhenOriginalExists() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        String testPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(testPath);
        Files.createDirectories(originalFile.getParent());
        Files.writeString(originalFile, "original content");

        setupCompleteWriteMocks(caseId, approvalId, candidateEvidenceId, testPath);

        PatternInformedTestWriteResult result = service.write(caseId, approvalId);

        assertEquals(2, result.version());
        assertEquals(
                "src/test/java/com/example/MyTestV2.java",
                result.writtenRelativePath()
        );
        assertFalse(result.existingFileOverwritten());
        assertTrue(result.fileWritten());
        assertFalse(result.testExecuted());
    }

    @Test
    void shouldNotOverwriteExistingFile() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        String testPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(testPath);
        Files.createDirectories(originalFile.getParent());
        String originalContent = "original content that should not be overwritten";
        Files.writeString(originalFile, originalContent);

        setupCompleteWriteMocks(caseId, approvalId, candidateEvidenceId, testPath);

        service.write(caseId, approvalId);

        String fileContent = Files.readString(originalFile);
        assertEquals(originalContent, fileContent);
    }

    @Test
    void shouldSavePreWriteEvidenceBeforeFileWrite() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        String testPath = "src/test/java/com/example/MyTest.java";

        setupCompleteWriteMocks(caseId, approvalId, candidateEvidenceId, testPath);

        service.write(caseId, approvalId);

        verify(evidenceService).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                eq("approved-pattern-informed-test-source"),
                anyString(),
                eq(true)
        );

        verify(evidenceService).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                eq("approved-pattern-informed-test-write-result"),
                anyString(),
                eq(true)
        );
    }

    @Test
    void shouldRenameClassForVersionedPath() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        String testPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(testPath);
        Files.createDirectories(originalFile.getParent());
        Files.writeString(originalFile, "original");

        setupCompleteWriteMocks(caseId, approvalId, candidateEvidenceId, testPath);

        PatternInformedTestWriteResult result = service.write(caseId, approvalId);

        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("Class renamed")));
    }

    @Test
    void shouldRecordAuditEvent() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID candidateEvidenceId = UUID.randomUUID();

        String testPath = "src/test/java/com/example/MyTest.java";

        setupCompleteWriteMocks(caseId, approvalId, candidateEvidenceId, testPath);

        service.write(caseId, approvalId);

        verify(auditService).record(
                eq(caseId),
                eq("PATTERN_INFORMED_TEST_WRITTEN"),
                anyString(),
                anyString()
        );
    }

    private void setupMocksWithCandidate(
            UUID caseId,
            UUID candidateEvidenceId,
            boolean correctHash
    ) {
        try {
            PatternInformedTestSourceCandidate candidate = correctHash
                    ? createCandidate(caseId, TestSourceReadiness.READY_FOR_REVIEW)
                    : createCandidateWithWrongHash(caseId);

            setupMocksWithSpecificCandidate(caseId, candidateEvidenceId, candidate);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setupMocksWithSpecificCandidate(
            UUID caseId,
            UUID candidateEvidenceId,
            PatternInformedTestSourceCandidate candidate
    ) throws Exception {
        EvidenceEntity candidateEvidence = new EvidenceEntity();
        candidateEvidence.setCaseId(caseId);
        candidateEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        candidateEvidence.setSource("pattern-informed-test-source-candidate");
        candidateEvidence.setContentText(objectMapper.writeValueAsString(candidate));

        when(evidenceService.list(caseId))
                .thenReturn(List.of(candidateEvidence));
    }

    private void setupCompleteWriteMocks(
            UUID caseId,
            UUID approvalId,
            UUID candidateEvidenceId,
            String testPath
    ) throws Exception {
        PatternInformedTestSourceCandidate candidate = createCandidateWithPath(
                caseId,
                testPath
        );

        EvidenceEntity candidateEvidence = new EvidenceEntity();
        candidateEvidence.setCaseId(caseId);
        candidateEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        candidateEvidence.setSource("pattern-informed-test-source-candidate");
        candidateEvidence.setContentText(objectMapper.writeValueAsString(candidate));

        SourceCheckoutResult checkout = new SourceCheckoutResult(
                caseId,
                "test-project",
                "repo",
                "branch",
                "sha",
                tempWorkspace.toString(),
                true
        );

        EvidenceEntity checkoutEvidence = new EvidenceEntity();
        checkoutEvidence.setCaseId(caseId);
        checkoutEvidence.setEvidenceType(EvidenceType.SOURCE_CHECKOUT);
        checkoutEvidence.setSource("source-checkout-metadata");
        checkoutEvidence.setContentText(objectMapper.writeValueAsString(checkout));

        when(evidenceService.list(caseId))
                .thenReturn(List.of(candidateEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedPatternInformedTestSource(
                caseId,
                candidateEvidenceId
        )).thenReturn(approval);

        when(gitService.hasChanges(any())).thenReturn(false);
    }

    private PatternInformedTestSourceCandidate createCandidate(
            UUID caseId,
            TestSourceReadiness readiness
    ) {
        String source = "public class MyTest {}";
        return new PatternInformedTestSourceCandidate(
                caseId,
                "repo",
                "sha",
                "pattern.java",
                "TargetClass",
                "targetMethod",
                "com.example",
                "MyTest",
                "testMethod",
                "src/test/java/com/example/MyTest.java",
                "JUnit 5",
                "MOCKITO_UNIT",
                source,
                calculateHash(source),
                readiness,
                0.85,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    private PatternInformedTestSourceCandidate createCandidateWithPath(
            UUID caseId,
            String testPath
    ) {
        String source = "public class MyTest {}";
        return new PatternInformedTestSourceCandidate(
                caseId,
                "repo",
                "sha",
                "pattern.java",
                "TargetClass",
                "targetMethod",
                "com.example",
                "MyTest",
                "testMethod",
                testPath,
                "JUnit 5",
                "MOCKITO_UNIT",
                source,
                calculateHash(source),
                TestSourceReadiness.READY_FOR_REVIEW,
                0.85,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    private PatternInformedTestSourceCandidate createCandidateWithWrongHash(
            UUID caseId
    ) {
        return new PatternInformedTestSourceCandidate(
                caseId,
                "repo",
                "sha",
                "pattern.java",
                "TargetClass",
                "targetMethod",
                "com.example",
                "MyTest",
                "testMethod",
                "src/test/java/com/example/MyTest.java",
                "JUnit 5",
                "MOCKITO_UNIT",
                "public class MyTest {}",
                "wrong-hash",
                TestSourceReadiness.READY_FOR_REVIEW,
                0.85,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    private PatternInformedTestSourceCandidate createCandidateWithConfidence(
            UUID caseId,
            double confidence
    ) {
        String source = "public class MyTest {}";
        return new PatternInformedTestSourceCandidate(
                caseId,
                "repo",
                "sha",
                "pattern.java",
                "TargetClass",
                "targetMethod",
                "com.example",
                "MyTest",
                "testMethod",
                "src/test/java/com/example/MyTest.java",
                "JUnit 5",
                "MOCKITO_UNIT",
                source,
                calculateHash(source),
                TestSourceReadiness.READY_FOR_REVIEW,
                confidence,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    private PatternInformedTestSourceCandidate createCandidateWithUnresolved(
            UUID caseId,
            List<String> unresolvedSymbols
    ) {
        String source = "public class MyTest {}";
        return new PatternInformedTestSourceCandidate(
                caseId,
                "repo",
                "sha",
                "pattern.java",
                "TargetClass",
                "targetMethod",
                "com.example",
                "MyTest",
                "testMethod",
                "src/test/java/com/example/MyTest.java",
                "JUnit 5",
                "MOCKITO_UNIT",
                source,
                calculateHash(source),
                TestSourceReadiness.READY_FOR_REVIEW,
                0.85,
                List.of(),
                List.of(),
                List.of(),
                unresolvedSymbols,
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    private String calculateHash(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

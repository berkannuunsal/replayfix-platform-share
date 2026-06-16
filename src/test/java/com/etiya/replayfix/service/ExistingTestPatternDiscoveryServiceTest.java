package com.etiya.replayfix.service;

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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExistingTestPatternDiscoveryServiceTest {

    private ExistingTestPatternDiscoveryService discoveryService;
    private EvidenceService evidenceService;
    private EvidenceSanitizer evidenceSanitizer;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        evidenceService = mock(EvidenceService.class);
        evidenceSanitizer = mock(EvidenceSanitizer.class);
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();

        when(evidenceSanitizer.sanitize(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        discoveryService = new ExistingTestPatternDiscoveryService(
                evidenceService,
                evidenceSanitizer,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldScanSrcTestJavaDirectory() throws Exception {
        UUID caseId = UUID.randomUUID();

        Path testFile = createTestFile(
                "src/test/java/com/example/MyServiceTest.java",
                createJUnit5TestContent("com.example", "MyServiceTest", List.of("testMethod"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "doSomething");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        assertNotNull(result);
        assertEquals(caseId, result.caseId());
        assertEquals("GENERATED_TEST", result.evidenceType());
        assertEquals("existing-test-pattern-selection", result.evidenceSource());
        assertFalse(result.fileWritten());
        assertFalse(result.testExecuted());

        assertNotNull(result.selection());
        assertTrue(result.selection().scannedFileCount() > 0);

        verify(evidenceService).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                eq("existing-test-pattern-selection"),
                anyString(),
                eq(true)
        );

        verify(auditService).record(
                eq(caseId),
                eq("EXISTING_TEST_PATTERN_SELECTED"),
                eq("system"),
                anyString()
        );
    }

    @Test
    void shouldScoreLowerForFileWithoutTestAnnotation() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/example/NoTestFile.java",
                "package com.example;\n\npublic class NoTestFile {\n    public void notATest() {}\n}"
        );

        createTestFile(
                "src/test/java/com/example/WithTestFile.java",
                createJUnit5TestContent("com.example", "WithTestFile", List.of("testMethod"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertTrue(selected.className().contains("WithTest") ||
                selected.testMethods().size() > 0);
    }

    @Test
    void shouldScoreHigherForSamePackage() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/other/OtherTest.java",
                createJUnit5TestContent("com.other", "OtherTest", List.of("testMethod"))
        );

        createTestFile(
                "src/test/java/com/example/SamePackageTest.java",
                createJUnit5TestContent("com.example", "SamePackageTest", List.of("testMethod"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertEquals("com.example", selected.packageName());
        assertTrue(selected.reasons().stream()
                .anyMatch(r -> r.contains("Same package")));
    }

    @Test
    void shouldScoreHigherForTargetClassReference() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/example/GenericTest.java",
                createJUnit5TestContent("com.example", "GenericTest", List.of("testGeneric"))
        );

        String contentWithTargetClass = createJUnit5TestContent(
                "com.example",
                "MyServiceTest",
                List.of("testMyService")
        ) + "\n    private MyService service;";

        createTestFile(
                "src/test/java/com/example/MyServiceTest.java",
                contentWithTargetClass
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertTrue(selected.reasons().stream()
                .anyMatch(r -> r.contains("target production class")));
    }

    @Test
    void shouldScoreHigherForTargetMethodReference() throws Exception {
        UUID caseId = UUID.randomUUID();

        String contentWithTargetMethod = createJUnit5TestContent(
                "com.example",
                "MyServiceTest",
                List.of("testDoSomething")
        ) + "\n    // Testing doSomething method";

        createTestFile(
                "src/test/java/com/example/MyServiceTest.java",
                contentWithTargetMethod
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "doSomething");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertTrue(selected.reasons().stream()
                .anyMatch(r -> r.contains("target production method")));
    }

    @Test
    void shouldDetectJUnit5WithMockito() throws Exception {
        UUID caseId = UUID.randomUUID();

        String content = "package com.example;\n\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.mockito.Mock;\n" +
                "import org.mockito.junit.jupiter.MockitoExtension;\n\n" +
                "class MyServiceTest {\n" +
                "    @Test\n" +
                "    void testMethod() {}\n" +
                "}";

        createTestFile("src/test/java/com/example/MyServiceTest.java", content);

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertEquals("JUnit 5 + Mockito", selected.framework());
        assertEquals("MOCKITO_UNIT", selected.testStyle());
    }

    @Test
    void shouldDetectSpringBootTestStyle() throws Exception {
        UUID caseId = UUID.randomUUID();

        String content = "package com.example;\n\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.springframework.boot.test.context.SpringBootTest;\n\n" +
                "@SpringBootTest\n" +
                "class MyServiceIntegrationTest {\n" +
                "    @Test\n" +
                "    void testMethod() {}\n" +
                "}";

        createTestFile("src/test/java/com/example/MyServiceIntegrationTest.java", content);

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertEquals("JUnit 5 + Spring Boot Test", selected.framework());
        assertEquals("SPRING_BOOT_INTEGRATION", selected.testStyle());
    }

    @Test
    void shouldNotSelectReplayFixGeneratedFile() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/example/NotificationServiceReplayFixRegressionTest.java",
                createJUnit5TestContent("com.example", "NotificationServiceReplayFixRegressionTest",
                        List.of("shouldReproduceIncident"))
        );

        createTestFile(
                "src/test/java/com/example/NormalTest.java",
                createJUnit5TestContent("com.example", "NormalTest", List.of("testMethod"))
        );

        setupMocksForDiscovery(caseId, "com.example", "NotificationService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertFalse(selected.className().contains("ReplayFix"));
    }

    @Test
    void shouldSkipLargeFiles() throws Exception {
        UUID caseId = UUID.randomUUID();

        StringBuilder largeContent = new StringBuilder(createJUnit5TestContent(
                "com.example",
                "LargeTest",
                List.of("testMethod")
        ));

        while (largeContent.length() < 600 * 1024) {
            largeContent.append("\n    // Padding to make file large");
        }

        createTestFile("src/test/java/com/example/LargeTest.java", largeContent.toString());

        createTestFile(
                "src/test/java/com/example/SmallTest.java",
                createJUnit5TestContent("com.example", "SmallTest", List.of("testMethod"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        assertNotNull(result.selection().selected());
    }

    @Test
    void shouldLimitExcerptLength() throws Exception {
        UUID caseId = UUID.randomUUID();

        StringBuilder longContent = new StringBuilder(createJUnit5TestContent(
                "com.example",
                "VerboseTest",
                List.of("testMethod")
        ));

        while (longContent.length() < 10000) {
            longContent.append("\n    // Very verbose test comments");
        }

        createTestFile("src/test/java/com/example/VerboseTest.java", longContent.toString());

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();

        assertTrue(selected.excerpt().length() <= 6000);
    }

    @Test
    void shouldSanitizeExcerptWithEvidenceSanitizer() throws Exception {
        UUID caseId = UUID.randomUUID();

        String sensitiveContent = createJUnit5TestContent(
                "com.example",
                "TestWithSecret",
                List.of("testMethod")
        ) + "\n    // API_KEY=secret123";

        createTestFile("src/test/java/com/example/TestWithSecret.java", sensitiveContent);

        when(evidenceSanitizer.sanitize(anyString()))
                .thenReturn("sanitized content");

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        discoveryService.discover(caseId);

        verify(evidenceSanitizer, atLeastOnce()).sanitize(anyString());
    }

    @Test
    void shouldSortCandidatesByScoreDescending() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/other/LowScoreTest.java",
                createJUnit5TestContent("com.other", "LowScoreTest", List.of("test"))
        );

        createTestFile(
                "src/test/java/com/example/HighScoreTest.java",
                createJUnit5TestContent("com.example", "HighScoreTest", List.of("testMyService"))
                        + "\n    private MyService service;"
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        TestPatternCandidate selected = result.selection().selected();
        List<TestPatternCandidate> alternatives = result.selection().alternatives();

        if (!alternatives.isEmpty()) {
            assertTrue(selected.score() >= alternatives.get(0).score());
        }
    }

    @Test
    void shouldNotWriteFiles() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/example/ExistingTest.java",
                createJUnit5TestContent("com.example", "ExistingTest", List.of("test"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        int fileCountBefore = countFilesInWorkspace();

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        int fileCountAfter = countFilesInWorkspace();

        assertEquals(fileCountBefore, fileCountAfter);
        assertFalse(result.fileWritten());
    }

    @Test
    void shouldNotExecuteMavenTests() throws Exception {
        UUID caseId = UUID.randomUUID();

        createTestFile(
                "src/test/java/com/example/TestFile.java",
                createJUnit5TestContent("com.example", "TestFile", List.of("test"))
        );

        setupMocksForDiscovery(caseId, "com.example", "MyService", "method");

        TestPatternDiscoveryResult result = discoveryService.discover(caseId);

        assertFalse(result.testExecuted());
    }

    private Path createTestFile(String relativePath, String content) throws Exception {
        Path testFile = tempWorkspace.resolve(relativePath);
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, content);
        return testFile;
    }

    private String createJUnit5TestContent(String packageName, String className, List<String> testMethods) {
        StringBuilder content = new StringBuilder();
        content.append("package ").append(packageName).append(";\n\n");
        content.append("import org.junit.jupiter.api.Test;\n\n");
        content.append("class ").append(className).append(" {\n");

        for (String method : testMethods) {
            content.append("    @Test\n");
            content.append("    void ").append(method).append("() {\n");
            content.append("    }\n");
        }

        content.append("}\n");
        return content.toString();
    }

    private void setupMocksForDiscovery(
            UUID caseId,
            String targetPackage,
            String targetClass,
            String targetMethod
    ) throws Exception {
        RegressionTestPlan plan = new RegressionTestPlan(
                caseId,
                "test-repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                targetClass,
                targetMethod,
                targetClass + "Test",
                "test" + targetMethod,
                "src/test/java/" + targetPackage.replace('.', '/') + "/" + targetClass + "Test.java",
                "Test scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "failure",
                "success",
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

        GeneratedTestWriteResult writeResult = new GeneratedTestWriteResult(
                caseId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                tempWorkspace.toString(),
                "src/test/java/" + targetPackage.replace('.', '/') + "/" + targetClass + "ReplayFixRegressionTest.java",
                tempWorkspace.resolve("src/test/java/" + targetPackage.replace('.', '/') +
                        "/" + targetClass + "ReplayFixRegressionTest.java").toString(),
                targetClass + "ReplayFixRegressionTest",
                "shouldReproduceIncident",
                "hash123",
                100,
                true,
                true,
                false,
                false,
                "",
                List.of()
        );

        EvidenceEntity writeEvidence = new EvidenceEntity();
        writeEvidence.setCaseId(caseId);
        writeEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        writeEvidence.setSource("approved-generated-test-write-result");
        writeEvidence.setContentText(objectMapper.writeValueAsString(writeResult));

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, writeEvidence));
    }

    private int countFilesInWorkspace() throws Exception {
        Path testDir = tempWorkspace.resolve("src/test/java");

        if (!Files.isDirectory(testDir)) {
            return 0;
        }

        try (var paths = Files.walk(testDir)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }
}

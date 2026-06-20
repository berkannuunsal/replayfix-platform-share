package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCandidateGitHistoryServiceTest {

    @TempDir
    Path repository;

    private SourceCandidateGitHistoryService service;
    private FlowAwareSourceDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(gitAvailable());
        service = new SourceCandidateGitHistoryService(
                new ReplayFixProperties(),
                new EvidenceSanitizer()
        );
        discoveryService = new FlowAwareSourceDiscoveryService();
    }

    @Test
    void collectsGitLogCommitsAndExtractsJiraKey() throws Exception {
        initRepository();
        writeJava("src/main/java/com/example/RegionService.java",
                "package com.example; public class RegionService { public void updateRegion() {} }\n");
        run("git", "add", ".");
        run("git", "commit", "-m", "FIZZMS-10228 update region flow");

        var result = service.collect(
                repository,
                List.of("src/main/java/com/example/RegionService.java"),
                discoveryService.discover(
                        repository,
                        List.of(new com.etiya.replayfix.model.SourceFlowAnchor(
                                "RegionService",
                                "DOMAIN_OBJECT",
                                "test"
                        )),
                        20
                ).javaFiles(),
                45,
                10,
                false
        );

        assertThat(result.recentCommits()).hasSize(1);
        assertThat(result.recentCommits().get(0).jiraKeys())
                .contains("FIZZMS-10228");
        assertThat(result.recentCommits().get(0).touchedCandidateFile())
                .isTrue();
    }

    @Test
    void mapsDiffHunkToMethodWhenPossible() throws Exception {
        initRepository();
        writeJava("src/main/java/com/example/RegionService.java",
                """
                        package com.example;
                        public class RegionService {
                            public void updateRegion() {
                                String value = "old";
                            }
                        }
                        """
        );
        run("git", "add", ".");
        run("git", "commit", "-m", "FIZZMS-1 initial");
        writeJava("src/main/java/com/example/RegionService.java",
                """
                        package com.example;
                        public class RegionService {
                            public void updateRegion() {
                                String value = "new";
                            }
                        }
                        """
        );
        run("git", "add", ".");
        run("git", "commit", "-m", "FIZZMS-10228 change method");

        var javaFiles = discoveryService.discover(
                repository,
                List.of(new com.etiya.replayfix.model.SourceFlowAnchor(
                        "RegionService",
                        "DOMAIN_OBJECT",
                        "test"
                )),
                20
        ).javaFiles();
        var result = service.collect(
                repository,
                List.of("src/main/java/com/example/RegionService.java"),
                javaFiles,
                45,
                10,
                true
        );

        assertThat(result.diffSnippets())
                .anySatisfy(snippet ->
                        assertThat(snippet.methodName()).isEqualTo("updateRegion"));
    }

    @Test
    void returnsMethodNotResolvedWhenMappingFails() {
        var snippet = service.mapDiffToMethod(
                "@@ -1,1 +100,1 @@\n+outside\n",
                new FlowAwareSourceDiscoveryService.JavaFileInfo(
                        "A.java",
                        "A",
                        List.of(),
                        java.util.Map.of(),
                        List.of(),
                        List.of(new FlowAwareSourceDiscoveryService.JavaMethodInfo(
                                "inside",
                                1,
                                5,
                                "public void inside()",
                                List.of(),
                                ""
                        ))
                )
        );

        assertThat(snippet).isEmpty();
    }

    @Test
    void returnsMethodNotResolvedWarningWhenDiffCannotMapToMethod()
            throws Exception {
        initRepository();
        writeJava("src/main/java/com/example/RegionService.java",
                """
                        package com.example;
                        public class RegionService {
                            private String field = "old";
                            public void updateRegion() {
                            }
                        }
                        """
        );
        run("git", "add", ".");
        run("git", "commit", "-m", "FIZZMS-1 initial");
        writeJava("src/main/java/com/example/RegionService.java",
                """
                        package com.example;
                        public class RegionService {
                            private String field = "new";
                            public void updateRegion() {
                            }
                        }
                        """
        );
        run("git", "add", ".");
        run("git", "commit", "-m", "FIZZMS-10228 field change");

        var result = service.collect(
                repository,
                List.of("src/main/java/com/example/RegionService.java"),
                java.util.Map.of(),
                45,
                10,
                true
        );

        assertThat(result.diffSnippets())
                .anySatisfy(snippet -> assertThat(snippet.warnings())
                        .contains(SourceCandidateGitHistoryService
                                .METHOD_NOT_RESOLVED));
    }

    private boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void initRepository() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@example.com");
        run("git", "config", "user.name", "Test User");
    }

    private void writeJava(String relativePath, String content) throws Exception {
        Path path = repository.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(
                    new String(process.getInputStream().readAllBytes())
            );
        }
    }
}

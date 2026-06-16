package com.etiya.replayfix.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VersionedTestPathResolverTest {

    private VersionedTestPathResolver resolver;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        resolver = new VersionedTestPathResolver();
    }

    @Test
    void shouldThrowWhenPathIsEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(tempWorkspace, "")
        );
    }

    @Test
    void shouldThrowWhenPathIsNotUnderSrcTestJava() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        tempWorkspace,
                        "src/main/java/MyClass.java"
                )
        );
    }

    @Test
    void shouldThrowWhenPathContainsTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        tempWorkspace,
                        "src/test/java/../../../etc/passwd"
                )
        );
    }

    @Test
    void shouldReturnVersion1WhenFileDoesNotExist() throws Exception {
        String proposedPath = "src/test/java/com/example/MyTest.java";

        VersionedTestPathResolver.VersionedPath result =
                resolver.resolve(tempWorkspace, proposedPath);

        assertEquals(proposedPath, result.relativePath());
        assertEquals(1, result.version());
        assertFalse(Files.exists(result.absolutePath()));
    }

    @Test
    void shouldReturnVersion2WhenOriginalFileExists() throws Exception {
        String proposedPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(proposedPath);
        Files.createDirectories(originalFile.getParent());
        Files.writeString(originalFile, "original content");

        VersionedTestPathResolver.VersionedPath result =
                resolver.resolve(tempWorkspace, proposedPath);

        assertEquals(
                "src/test/java/com/example/MyTestV2.java",
                result.relativePath()
        );
        assertEquals(2, result.version());
        assertFalse(Files.exists(result.absolutePath()));
    }

    @Test
    void shouldReturnVersion3WhenV2Exists() throws Exception {
        String proposedPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(proposedPath);
        Files.createDirectories(originalFile.getParent());
        Files.writeString(originalFile, "original");

        Path v2File = tempWorkspace.resolve(
                "src/test/java/com/example/MyTestV2.java"
        );
        Files.writeString(v2File, "v2 content");

        VersionedTestPathResolver.VersionedPath result =
                resolver.resolve(tempWorkspace, proposedPath);

        assertEquals(
                "src/test/java/com/example/MyTestV3.java",
                result.relativePath()
        );
        assertEquals(3, result.version());
    }

    @Test
    void shouldThrowWhen100VersionsExist() throws Exception {
        String proposedPath = "src/test/java/com/example/MyTest.java";

        Path originalFile = tempWorkspace.resolve(proposedPath);
        Files.createDirectories(originalFile.getParent());
        Files.writeString(originalFile, "original");

        for (int i = 2; i <= 100; i++) {
            Path versionFile = tempWorkspace.resolve(
                    "src/test/java/com/example/MyTestV" + i + ".java"
            );
            Files.writeString(versionFile, "version " + i);
        }

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(tempWorkspace, proposedPath)
        );
    }

    @Test
    void shouldNotEscapeWorkspace() throws Exception {
        String proposedPath = "src/test/java/com/example/../../../../../../etc/passwd.java";

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(tempWorkspace, proposedPath)
        );
    }
}

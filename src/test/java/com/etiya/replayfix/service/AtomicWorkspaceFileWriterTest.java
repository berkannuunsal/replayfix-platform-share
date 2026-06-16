package com.etiya.replayfix.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicWorkspaceFileWriterTest {

    private AtomicWorkspaceFileWriter writer;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        writer = new AtomicWorkspaceFileWriter();
    }

    @Test
    void shouldWriteNewFileInWorkspace() throws Exception {
        String relativePath = "src/test/java/TestFile.java";
        String content = "public class TestFile {}";

        Path result = writer.writeNewFile(
                tempWorkspace,
                relativePath,
                content
        );

        assertTrue(Files.exists(result));
        String written = Files.readString(result);
        assertEquals(content, written);
    }

    @Test
    void shouldCreateParentDirectories() {
        String relativePath = "src/test/java/com/example/deep/TestFile.java";
        String content = "public class TestFile {}";

        Path result = writer.writeNewFile(
                tempWorkspace,
                relativePath,
                content
        );

        assertTrue(Files.exists(result));
        assertTrue(Files.exists(result.getParent()));
    }

    @Test
    void shouldThrowExceptionWhenFileAlreadyExists() throws Exception {
        String relativePath = "src/test/java/ExistingFile.java";
        String content = "content";

        Path target = tempWorkspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "existing content");

        assertThrows(
                IllegalStateException.class,
                () -> writer.writeNewFile(
                        tempWorkspace,
                        relativePath,
                        content
                )
        );
    }

    @Test
    void shouldRejectPathTraversal() {
        String relativePath = "../outside/TestFile.java";
        String content = "content";

        assertThrows(
                IllegalArgumentException.class,
                () -> writer.writeNewFile(
                        tempWorkspace,
                        relativePath,
                        content
                )
        );
    }

    @Test
    void shouldRejectAbsolutePath() {
        String relativePath = "/absolute/path/TestFile.java";
        String content = "content";

        assertThrows(
                IllegalArgumentException.class,
                () -> writer.writeNewFile(
                        tempWorkspace,
                        relativePath,
                        content
                )
        );
    }

    @Test
    void shouldWriteExpectedContent() throws Exception {
        String relativePath = "src/test/java/ContentTest.java";
        String content = "Line 1\nLine 2\nLine 3";

        Path result = writer.writeNewFile(
                tempWorkspace,
                relativePath,
                content
        );

        String written = Files.readString(result);
        assertEquals(content, written);
        assertTrue(written.contains("Line 1"));
        assertTrue(written.contains("Line 2"));
        assertTrue(written.contains("Line 3"));
    }
}

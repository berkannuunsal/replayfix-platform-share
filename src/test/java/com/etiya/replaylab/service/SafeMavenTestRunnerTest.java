package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafeMavenTestRunnerTest {

    private SafeMavenTestRunner runner;
    private ReplayLabProperties properties;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        properties = mock(ReplayLabProperties.class);
        ReplayLabProperties.Policy policy = new ReplayLabProperties.Policy();
        policy.setMavenExecutable("mvn");
        policy.setLocalTestTimeoutSeconds(10);
        policy.setLocalTestMaxOutputChars(1000);
        when(properties.getPolicy()).thenReturn(policy);

        runner = new SafeMavenTestRunner(properties);
    }

    @Test
    void shouldAcceptValidJavaIdentifiers() {
        assertDoesNotThrow(() ->
                runner.runSingleTest(
                        tempWorkspace,
                        "MyTestClass",
                        "testMethod"
                )
        );
    }

    @Test
    void shouldRejectTestClassWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "Test;rm -rf",
                        "method"
                )
        );
    }

    @Test
    void shouldRejectTestClassWithAmpersand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "Test&echo",
                        "method"
                )
        );
    }

    @Test
    void shouldRejectTestClassWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "Test Class",
                        "method"
                )
        );
    }

    @Test
    void shouldRejectTestMethodWithShellCharacter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "TestClass",
                        "method|whoami"
                )
        );
    }

    @Test
    void shouldThrowWhenWorkspaceDoesNotExist() {
        Path nonExistent = tempWorkspace.resolve("does-not-exist");

        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        nonExistent,
                        "TestClass",
                        "testMethod"
                )
        );
    }

    @Test
    void shouldRejectUnsafeMavenExecutable() {
        ReplayLabProperties.Policy policy = new ReplayLabProperties.Policy();
        policy.setMavenExecutable("mvn; rm -rf /");
        policy.setLocalTestTimeoutSeconds(10);
        policy.setLocalTestMaxOutputChars(1000);
        when(properties.getPolicy()).thenReturn(policy);

        SafeMavenTestRunner unsafeRunner = new SafeMavenTestRunner(properties);

        assertThrows(
                IllegalArgumentException.class,
                () -> unsafeRunner.runSingleTest(
                        tempWorkspace,
                        "TestClass",
                        "testMethod"
                )
        );
    }

    @Test
    void shouldRejectExecutableWithPipe() {
        ReplayLabProperties.Policy policy = new ReplayLabProperties.Policy();
        policy.setMavenExecutable("mvn | cat");
        policy.setLocalTestTimeoutSeconds(10);
        policy.setLocalTestMaxOutputChars(1000);
        when(properties.getPolicy()).thenReturn(policy);

        SafeMavenTestRunner unsafeRunner = new SafeMavenTestRunner(properties);

        assertThrows(
                IllegalArgumentException.class,
                () -> unsafeRunner.runSingleTest(
                        tempWorkspace,
                        "TestClass",
                        "testMethod"
                )
        );
    }

    @Test
    void shouldRejectEmptyTestClass() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "",
                        "testMethod"
                )
        );
    }

    @Test
    void shouldRejectEmptyTestMethod() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.runSingleTest(
                        tempWorkspace,
                        "TestClass",
                        ""
                )
        );
    }
}

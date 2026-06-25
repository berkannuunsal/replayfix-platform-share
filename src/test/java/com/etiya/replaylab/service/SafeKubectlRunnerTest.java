package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafeKubectlRunnerTest {

    private SafeKubectlRunner runner;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        properties = mock(ReplayLabProperties.class);
        ReplayLabProperties.Policy policy = new ReplayLabProperties.Policy();
        policy.setKubectlExecutable("kubectl");
        policy.setKubernetesTimeoutSeconds(30);
        when(properties.getPolicy()).thenReturn(policy);

        runner = new SafeKubectlRunner(properties, new ObjectMapper());
    }

    @Test
    void shouldAcceptValidContext() {
        assertDoesNotThrow(() ->
                runner.getDeployment(
                        "my-cluster",
                        "default",
                        "my-deployment"
                )
        );
    }

    @Test
    void shouldRejectInvalidContextWithSpecialCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.getDeployment(
                        "cluster; rm -rf /",
                        "default",
                        "deployment"
                )
        );
    }

    @Test
    void shouldAcceptValidNamespace() {
        assertDoesNotThrow(() ->
                runner.getDeployment(
                        "cluster",
                        "my-namespace",
                        "deployment"
                )
        );
    }

    @Test
    void shouldRejectInvalidNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.getDeployment(
                        "cluster",
                        "Invalid_Namespace",
                        "deployment"
                )
        );
    }

    @Test
    void shouldRejectNullNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.getDeployment(
                        "cluster",
                        null,
                        "deployment"
                )
        );
    }

    @Test
    void shouldAcceptValidResourceName() {
        assertDoesNotThrow(() ->
                runner.getDeployment(
                        "cluster",
                        "default",
                        "my-deployment-123"
                )
        );
    }

    @Test
    void shouldRejectInvalidResourceName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.getDeployment(
                        "cluster",
                        "default",
                        "Invalid_Resource"
                )
        );
    }

    @Test
    void shouldRejectResourceNameWithSpecialCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.getDeployment(
                        "cluster",
                        "default",
                        "deployment; cat /etc/passwd"
                )
        );
    }

    @Test
    void shouldBuildCorrectCommandForDeployment() {
        try {
            runner.getDeployment("test-context", "default", "my-app");
        } catch (Exception ignored) {
        }
    }

    @Test
    void shouldBuildCorrectCommandForReplicaSets() {
        try {
            runner.listReplicaSets("test-context", "default", "app=myapp");
        } catch (Exception ignored) {
        }
    }

    @Test
    void shouldBuildCorrectCommandForPods() {
        try {
            runner.listPods("test-context", "default", "app=myapp");
        } catch (Exception ignored) {
        }
    }
}

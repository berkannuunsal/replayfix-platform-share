package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBackendDemoPrGitOperationsTest {

    @Test
    void gitCloneUsesAskPassAndSanitizesAuthenticationFailure()
            throws Exception {
        Path root = Files.createTempDirectory("replaylab-backend-demo-pr-test-");
        Path capture = root.resolve("capture.txt");
        Path fakeGit = fakeGit(root, capture);

        ReplayLabProperties properties = new ReplayLabProperties();
        properties.getIntegrations().getBitbucket()
                .setBaseUrl("https://bitbucket.company.com");
        properties.getIntegrations().getBitbucket()
                .setUsername("berkan.unsal@company.com");
        properties.getIntegrations().getBitbucket()
                .setToken("secret-token-value");
        properties.getIntegrations().getBitbucket()
                .setGitExecutable(fakeGit.toString());
        ProcessBackendDemoPrGitOperations operations =
                new ProcessBackendDemoPrGitOperations(properties);

        BackendDemoPrGitOperations.BackendDemoPrGitResult result =
                operations.commitPushAndPrepareIntegrationBranch(
                        "DCE",
                        "backend",
                        "master",
                        "test2",
                        "bugfix/FIZZMS-10228",
                        "Integration/test2/FIZZMS-10228",
                        false,
                        false,
                        "ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228ReplayLabDemoRegressionTest.java",
                        "safe test",
                        "FIZZMS-10228: safe demo"
                );

        String captured = Files.readString(capture);
        assertThat(captured)
                .contains("USERNAME=berkan.unsal@company.com")
                .contains("TOKEN=secret-token-value")
                .contains("TERMINAL=0")
                .contains("ASKPASS=");
        assertThat(result.warning())
                .contains("bitbucketBaseUrlConfigured=true")
                .contains("bitbucketUsernameConfigured=true")
                .contains("bitbucketTokenConfigured=true")
                .contains("effectiveUsernameMasked=b****@company.com")
                .contains("gitCloneHost=bitbucket.company.com")
                .contains("credentialSource=BITBUCKET_USERNAME/BITBUCKET_TOKEN");
        assertThat(result.error())
                .contains("BITBUCKET_REPOSITORY_CLONE_FAILED")
                .doesNotContain("secret-token-value")
                .doesNotContain("berkan.unsal@company.com:secret-token-value")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie");
    }

    private Path fakeGit(Path root, Path capture) throws Exception {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        Path script = root.resolve(windows ? "fake-git.cmd" : "fake-git.sh");
        if (windows) {
            String capturePath = capture.toString().replace("\\", "\\\\");
            Files.writeString(
                    script,
                    """
                            @echo off
                            >> "%s" echo ASKPASS=%%GIT_ASKPASS%%
                            >> "%s" echo TERMINAL=%%GIT_TERMINAL_PROMPT%%
                            >> "%s" echo USERNAME=%%REPLAYLAB_GIT_USERNAME%%
                            >> "%s" echo TOKEN=%%REPLAYLAB_GIT_TOKEN%%
                            echo fatal: Authentication failed for 'https://%%REPLAYLAB_GIT_USERNAME%%:%%REPLAYLAB_GIT_TOKEN%%@bitbucket.company.com/scm/dce/backend.git/' Authorization Cookie
                            exit /b 1
                            """.formatted(capturePath, capturePath, capturePath, capturePath),
                    StandardCharsets.UTF_8
            );
        } else {
            String capturePath = capture.toString().replace("'", "'\"'\"'");
            Files.writeString(
                    script,
                    """
                            #!/bin/sh
                            {
                              echo "ASKPASS=$GIT_ASKPASS"
                              echo "TERMINAL=$GIT_TERMINAL_PROMPT"
                              echo "USERNAME=$REPLAYLAB_GIT_USERNAME"
                              echo "TOKEN=$REPLAYLAB_GIT_TOKEN"
                            } >> '%s'
                            echo "fatal: Authentication failed for 'https://$REPLAYLAB_GIT_USERNAME:$REPLAYLAB_GIT_TOKEN@bitbucket.company.com/scm/dce/backend.git/' Authorization Cookie"
                            exit 1
                            """.formatted(capturePath),
                    StandardCharsets.UTF_8
            );
        }
        script.toFile().setExecutable(true);
        return script;
    }
}

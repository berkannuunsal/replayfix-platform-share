package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import java.util.List;

public interface BitbucketClient {
    PullRequestResult createPullRequest(
            Target target,
            String sourceBranch,
            String destinationBranch,
            String title,
            String description,
            List<String> reviewers
    );
}

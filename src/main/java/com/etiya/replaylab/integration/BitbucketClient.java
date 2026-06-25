package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties.Target;
import com.etiya.replaylab.model.BitbucketConnectionTestResult;
import com.etiya.replaylab.model.BitbucketRepositoryInfo;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketFileUpdateResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketMergeResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestCommentResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestResult;

import java.util.List;

public interface BitbucketClient {

    List<BitbucketRepositoryInfo> listRepositories();

    BitbucketConnectionTestResult testConnection();

    BitbucketBranchCheckResult branchExists(
            String projectKey,
            String repositorySlug,
            String branchName
    );

    BitbucketBranchCreateResult createBranch(
            String projectKey,
            String repositorySlug,
            String branchName,
            String startPoint
    );

    BitbucketMergeResult mergeBranches(
            String projectKey,
            String repositorySlug,
            String sourceBranch,
            String targetBranch
    );

    BitbucketFileUpdateResult updateFile(
            String projectKey,
            String repositorySlug,
            String branchName,
            String filePath,
            String content,
            String commitMessage
    );

    default PullRequestResult findOpenPullRequest(
            String projectKey,
            String repositorySlug,
            String sourceBranch,
            String destinationBranch
    ) {
        return new PullRequestResult("", "", "");
    }

    PullRequestResult createPullRequest(
            Target target,
            String sourceBranch,
            String destinationBranch,
            String title,
            String description,
            List<String> reviewers
    );

    PullRequestResult createPullRequest(
            String projectKey,
            String repositorySlug,
            String sourceBranch,
            String destinationBranch,
            String title,
            String description,
            List<String> reviewers
    );

    default PullRequestCommentResult addPullRequestComment(
            String projectKey,
            String repositorySlug,
            String pullRequestId,
            String text
    ) {
        return new PullRequestCommentResult(false, "", "", List.of("BITBUCKET_PR_COMMENT_NOT_IMPLEMENTED"));
    }
}

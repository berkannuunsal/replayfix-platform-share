package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.BitbucketConnectionTestResult;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketFileUpdateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketMergeResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestCommentResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;

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

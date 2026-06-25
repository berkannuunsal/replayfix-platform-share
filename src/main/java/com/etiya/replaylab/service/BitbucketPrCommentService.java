package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.PrOutcomeSummaryRequest;
import com.etiya.replaylab.api.dto.PrOutcomeSummaryResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.PullRequestCommentResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BitbucketPrCommentService {

    private final BitbucketClient bitbucketClient;
    private final ReplayLabProperties properties;
    private final ReplayLabPrOutcomeSummaryService summaryService;

    public BitbucketPrCommentService(
            BitbucketClient bitbucketClient,
            ReplayLabProperties properties,
            ReplayLabPrOutcomeSummaryService summaryService
    ) {
        this.bitbucketClient = bitbucketClient;
        this.properties = properties;
        this.summaryService = summaryService;
    }

    public PrOutcomeSummaryResponse preview(UUID caseId, PrOutcomeSummaryRequest request) {
        String summary = summaryService.buildSummary(caseId, request);
        return response(caseId, request, summary, false, "", false,
                List.of(), List.of("PREVIEW_ONLY_NO_PR_COMMENT_CREATED"));
    }

    public PrOutcomeSummaryResponse comment(UUID caseId, PrOutcomeSummaryRequest request) {
        List<String> blockers = validateCreate(request);
        String summary = summaryService.buildSummary(caseId, request);
        if (!blockers.isEmpty()) {
            return response(caseId, request, summary, false, "", false, blockers, List.of());
        }
        PullRequestCommentResult result = bitbucketClient.addPullRequestComment(
                request.projectKey(),
                request.repositorySlug(),
                request.pullRequestId(),
                summary
        );
        List<String> warnings = new ArrayList<>(sanitize(result.warnings()));
        if (!result.created()) {
            warnings.add("BITBUCKET_PR_SUMMARY_COMMENT_FAILED");
        }
        return response(caseId, request, summary, result.created(),
                result.url(), false, List.of(), warnings);
    }

    private List<String> validateCreate(PrOutcomeSummaryRequest request) {
        List<String> blockers = new ArrayList<>();
        if (request == null) {
            blockers.add("PR_OUTCOME_SUMMARY_REQUEST_REQUIRED");
            return blockers;
        }
        if (isBlank(request.requestedBy())) blockers.add("REQUESTED_BY_REQUIRED");
        if (!request.confirmCreate()) blockers.add("CONFIRM_CREATE_REQUIRED");
        if (!request.guardrailsAccepted()) blockers.add("GUARDRAILS_ACCEPTED_REQUIRED");
        if (isBlank(request.projectKey())) blockers.add("BITBUCKET_PROJECT_KEY_REQUIRED");
        if (isBlank(request.repositorySlug())) blockers.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
        if (isBlank(request.pullRequestId())) blockers.add("BITBUCKET_PULL_REQUEST_ID_REQUIRED");
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isBitbucketPrCreateEnabled()) {
            blockers.add("REAL_ACTIONS_DISABLED");
        }
        return blockers;
    }

    private PrOutcomeSummaryResponse response(
            UUID caseId,
            PrOutcomeSummaryRequest request,
            String summary,
            boolean commentCreated,
            String commentUrl,
            boolean descriptionUpdated,
            List<String> blockers,
            List<String> warnings
    ) {
        return new PrOutcomeSummaryResponse(
                caseId,
                request == null ? "" : nullToBlank(request.pullRequestId()),
                request == null ? "" : nullToBlank(request.pullRequestUrl()),
                summary,
                commentCreated,
                nullToBlank(commentUrl),
                descriptionUpdated,
                blockers == null ? List.of() : List.copyOf(blockers),
                warnings == null ? List.of() : List.copyOf(warnings),
                commentCreated
                        ? List.of("Review the ReplayLab summary comment on the draft PR.")
                        : List.of("Create the ReplayLab PR summary comment after guarded approval."),
                Instant.now()
        );
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::sanitize).toList();
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)authorization[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)bearer[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)cookie[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)token[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)password[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)secret[^\\s,;]*", "[redacted]")
                .replaceAll("https?://[^\\s]*@[^\\s]+", "[redacted-url]");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

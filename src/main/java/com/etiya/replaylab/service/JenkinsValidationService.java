package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.JenkinsValidationRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationResultRefreshRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationResponse;
import com.etiya.replaylab.api.dto.JenkinsValidationStatusResponse;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryCommentRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.integration.JenkinsClient;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.IntegrationModels.JenkinsBuildStatus;
import com.etiya.replaylab.model.IntegrationModels.JenkinsQueueItem;
import com.etiya.replaylab.model.IntegrationModels.JenkinsTriggerResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestCommentResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JenkinsValidationService {

    private static final String SOURCE = "replaylab-jenkins-validation";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JenkinsClient jenkinsClient;
    private final BitbucketClient bitbucketClient;
    private final JiraClient jiraClient;
    private final ReplayLabProperties properties;
    private final ObjectMapper objectMapper;

    public JenkinsValidationService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JenkinsClient jenkinsClient,
            BitbucketClient bitbucketClient,
            JiraClient jiraClient,
            ReplayLabProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.jenkinsClient = jenkinsClient;
        this.bitbucketClient = bitbucketClient;
        this.jiraClient = jiraClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JenkinsValidationResponse preview(
            UUID caseId,
            JenkinsValidationRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JenkinsValidationRequest safeRequest = safe(request);
        List<String> blockers = previewBlockers(safeRequest);
        String jobName = resolveJobName(safeRequest);
        if (jobName.isBlank()) {
            blockers.add("JENKINS_VALIDATION_JOB_NOT_CONFIGURED");
        }
        JenkinsValidationResponse response = response(
                replayCase.getId(),
                safeRequest,
                true,
                false,
                jobName,
                "",
                "",
                plannedParameters(replayCase.getId(), safeRequest),
                blockers,
                List.of("PREVIEW_ONLY_NO_JENKINS_TRIGGER"),
                List.of("Review validation plan and trigger only after human approval.")
        );
        saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_PREVIEW, response);
        return response;
    }

    @Transactional
    public JenkinsValidationResponse trigger(
            UUID caseId,
            JenkinsValidationRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JenkinsValidationRequest safeRequest = safe(request);
        validateTrigger(safeRequest);
        String jobName = resolveJobName(safeRequest);
        if (jobName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "JENKINS_VALIDATION_JOB_NOT_CONFIGURED"
            );
        }
        Map<String, String> parameters = plannedParameters(replayCase.getId(), safeRequest);
        JenkinsTriggerResult result = jenkinsClient.triggerValidation(jobName, parameters);
        List<String> warnings = new ArrayList<>(sanitize(result.warnings()));
        if (!result.triggered()) {
            warnings.add("JENKINS_VALIDATION_TRIGGER_FAILED");
        }
        JenkinsValidationResponse response = response(
                replayCase.getId(),
                safeRequest,
                false,
                result.triggered(),
                jobName,
                safe(result.queueUrl()),
                safe(result.buildUrl()),
                parameters,
                List.of(),
                warnings,
                result.triggered()
                        ? List.of("Monitor Jenkins validation result before merge.")
                        : List.of("Review Jenkins trigger failure and retry only after human approval.")
        );
        saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_TRIGGER, response);
        return response;
    }

    @Transactional(readOnly = true)
    public JenkinsValidationStatusResponse status(UUID caseId) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        Optional<EvidenceEntity> latest = latestEvidence(
                caseId,
                EvidenceType.JENKINS_VALIDATION_RESULT,
                EvidenceType.JENKINS_VALIDATION_TRIGGER,
                EvidenceType.JENKINS_VALIDATION_PREVIEW
        );
        if (latest.isEmpty()) {
            return statusResponse(
                    replayCase.getId(),
                    replayCase.getJiraKey(),
                    "NOT_TRIGGERED",
                    "", "", "", "", "", "", 0,
                    "", "", "",
                    List.of(),
                    List.of(),
                    List.of("Trigger Jenkins validation preview.")
            );
        }
        return statusFromEvidence(replayCase, latest.get());
    }

    @Transactional
    public JenkinsValidationStatusResponse refresh(
            UUID caseId,
            JenkinsValidationResultRefreshRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JenkinsValidationResultRefreshRequest safeRequest = safe(request);
        String buildUrl = safe(safeRequest.jenkinsBuildUrl());
        String buildNumber = safe(safeRequest.buildNumber());
        String queueUrl = safe(safeRequest.jenkinsQueueUrl());
        List<String> warnings = new ArrayList<>();
        String status = "UNKNOWN";
        long durationMillis = 0;
        long timestampMillis = 0;

        if (buildUrl.isBlank() && !queueUrl.isBlank()) {
            JenkinsQueueItem queueItem = jenkinsClient.getQueueItem(queueUrl);
            warnings.addAll(sanitize(queueItem.warnings()));
            if (queueItem.executableAvailable()) {
                buildUrl = safe(queueItem.buildUrl());
                buildNumber = safe(queueItem.buildNumber());
            } else {
                status = "QUEUED";
            }
        }

        if (!buildUrl.isBlank()) {
            JenkinsBuildStatus build = jenkinsClient.getBuildStatusByUrl(buildUrl);
            warnings.addAll(sanitize(build.warnings()));
            status = normalizeValidationStatus(build.status());
            buildUrl = safe(build.url()).isBlank() ? buildUrl : safe(build.url());
            buildNumber = safe(build.buildNumber()).isBlank() ? buildNumber : safe(build.buildNumber());
            durationMillis = build.durationMillis();
            timestampMillis = build.timestampMillis();
        } else if (status.equals("UNKNOWN") && !buildNumber.isBlank()) {
            JenkinsBuildStatus build = jenkinsClient.getBuildStatus(
                    safeRequest.jenkinsJobName(),
                    buildNumber
            );
            warnings.addAll(sanitize(build.warnings()));
            status = normalizeValidationStatus(build.status());
            buildUrl = safe(build.url());
            durationMillis = build.durationMillis();
            timestampMillis = build.timestampMillis();
        }

        JenkinsValidationStatusResponse response = statusResponse(
                replayCase.getId(),
                firstNonBlank(safeRequest.defectKey(), replayCase.getJiraKey()),
                status,
                safeRequest.jenkinsJobName(),
                queueUrl,
                buildUrl,
                buildNumber,
                timestampMillis > 0 ? Instant.ofEpochMilli(timestampMillis).toString() : "",
                finishedAt(status, timestampMillis, durationMillis),
                durationMillis,
                safeRequest.sourceBranch(),
                safeRequest.targetBranch(),
                safeRequest.pullRequestId(),
                List.of(),
                unique(warnings),
                nextActionsForStatus(status)
        );
        saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_RESULT, response);
        return response;
    }

    @Transactional
    public JenkinsValidationSummaryResponse summaryPreview(
            UUID caseId,
            JenkinsValidationSummaryRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JenkinsValidationSummaryRequest safeRequest = safe(request);
        String summary = buildSummary(replayCase.getId(), safeRequest);
        JenkinsValidationSummaryResponse response = summaryResponse(
                replayCase.getId(),
                safeRequest,
                summary,
                false,
                "",
                false,
                List.of(),
                List.of("PREVIEW_ONLY_NO_VALIDATION_SUMMARY_COMMENT"),
                List.of("Comment validation summary only after human approval.")
        );
        saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_SUMMARY_PREVIEW, response);
        return response;
    }

    @Transactional
    public JenkinsValidationSummaryResponse summaryComment(
            UUID caseId,
            JenkinsValidationSummaryCommentRequest request
    ) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JenkinsValidationSummaryCommentRequest safeRequest = safe(request);
        validateSummaryComment(safeRequest);
        String summary = latestSummary(caseId)
                .orElseGet(() -> buildSummaryFromStatus(replayCase, safeRequest));
        List<String> warnings = new ArrayList<>();
        boolean prCommentCreated = false;
        String prCommentUrl = "";
        boolean jiraCommentCreated = false;

        if (safeRequest.commentToPr()) {
            PullRequestCommentResult result = bitbucketClient.addPullRequestComment(
                    safeRequest.projectKey(),
                    safeRequest.repositorySlug(),
                    safeRequest.pullRequestId(),
                    summary
            );
            prCommentCreated = result.created();
            prCommentUrl = safe(result.url());
            warnings.addAll(sanitize(result.warnings()));
            if (!result.created()) {
                warnings.add("JENKINS_VALIDATION_PR_COMMENT_FAILED");
            } else {
                saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_PR_COMMENT,
                        Map.of("pullRequestId", safeRequest.pullRequestId(), "commentUrl", prCommentUrl));
            }
        }

        if (safeRequest.commentToJira()) {
            try {
                jiraClient.addComment(safeRequest.jiraKey(), summary);
                jiraCommentCreated = true;
                saveEvidence(replayCase.getId(), EvidenceType.JENKINS_VALIDATION_JIRA_COMMENT,
                        Map.of("jiraKey", safeRequest.jiraKey()));
            } catch (Exception exception) {
                warnings.add("JENKINS_VALIDATION_JIRA_COMMENT_FAILED");
                warnings.add(safe(rootCauseMessage(exception)));
            }
        }

        JenkinsValidationSummaryRequest synthetic = new JenkinsValidationSummaryRequest(
                safeRequest.requestedBy(),
                safeRequest.projectKey(),
                safeRequest.repositorySlug(),
                firstNonBlank(safeRequest.jiraKey(), replayCase.getJiraKey()),
                "",
                safeRequest.pullRequestId(),
                safeRequest.pullRequestUrl(),
                "", "", "", "", "UNKNOWN", List.of(), "", 0
        );
        return summaryResponse(
                replayCase.getId(),
                synthetic,
                summary,
                prCommentCreated,
                prCommentUrl,
                jiraCommentCreated,
                List.of(),
                unique(warnings),
                List.of("Review PR and Jira validation summary before merge.")
        );
    }

    private void validateTrigger(JenkinsValidationRequest request) {
        List<String> errors = previewBlockers(request);
        if (!request.confirmTrigger()) {
            errors.add("CONFIRM_TRIGGER_REQUIRED");
        }
        if (!request.guardrailsAccepted()) {
            errors.add("GUARDRAILS_ACCEPTED_REQUIRED");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.join(",", errors)
            );
        }
        if (!properties.getRealActions().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REAL_ACTIONS_DISABLED");
        }
        if (!properties.getRealActions().isJenkinsValidationTriggerEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "JENKINS_TRIGGER_DISABLED");
        }
    }

    private void validateSummaryComment(JenkinsValidationSummaryCommentRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.requestedBy())) errors.add("REQUESTED_BY_REQUIRED");
        if (!request.confirmComment()) errors.add("CONFIRM_COMMENT_REQUIRED");
        if (!request.guardrailsAccepted()) errors.add("GUARDRAILS_ACCEPTED_REQUIRED");
        if (request.commentToPr()) {
            if (isBlank(request.projectKey())) errors.add("BITBUCKET_PROJECT_KEY_REQUIRED");
            if (isBlank(request.repositorySlug())) errors.add("BITBUCKET_REPOSITORY_SLUG_REQUIRED");
            if (isBlank(request.pullRequestId())) errors.add("PULL_REQUEST_ID_REQUIRED");
        }
        if (request.commentToJira() && isBlank(request.jiraKey())) {
            errors.add("JIRA_KEY_REQUIRED");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(",", errors));
        }
        if (!properties.getRealActions().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REAL_ACTIONS_DISABLED");
        }
    }

    private JenkinsValidationStatusResponse statusFromEvidence(
            ReplayCaseEntity replayCase,
            EvidenceEntity evidence
    ) {
        JsonNode node = readJson(evidence.getContentText());
        String status = firstNonBlank(
                text(node, "validationStatus"),
                text(node, "status"),
                bool(node, "triggered") ? "QUEUED" : ""
        );
        if (status.isBlank() && evidence.getEvidenceType() == EvidenceType.JENKINS_VALIDATION_PREVIEW) {
            status = "NOT_TRIGGERED";
        }
        return statusResponse(
                replayCase.getId(),
                firstNonBlank(text(node, "defectKey"), replayCase.getJiraKey()),
                normalizeValidationStatus(status),
                text(node, "jenkinsJobName"),
                text(node, "jenkinsQueueUrl"),
                text(node, "jenkinsBuildUrl"),
                text(node, "buildNumber"),
                text(node, "startedAt"),
                text(node, "finishedAt"),
                node.path("durationMillis").asLong(0),
                text(node, "sourceBranch"),
                text(node, "targetBranch"),
                text(node, "pullRequestId"),
                list(node, "blockers"),
                list(node, "warnings"),
                list(node, "nextActions")
        );
    }

    private JenkinsValidationStatusResponse statusResponse(
            UUID caseId,
            String defectKey,
            String validationStatus,
            String jobName,
            String queueUrl,
            String buildUrl,
            String buildNumber,
            String startedAt,
            String finishedAt,
            long durationMillis,
            String sourceBranch,
            String targetBranch,
            String pullRequestId,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new JenkinsValidationStatusResponse(
                caseId,
                safe(defectKey),
                normalizeValidationStatus(validationStatus),
                safe(jobName),
                safe(queueUrl),
                safe(buildUrl),
                safe(buildNumber),
                safe(startedAt),
                safe(finishedAt),
                durationMillis,
                safe(sourceBranch),
                safe(targetBranch),
                safe(pullRequestId),
                unique(blockers),
                sanitize(unique(warnings)),
                unique(nextActions),
                Instant.now()
        );
    }

    private JenkinsValidationSummaryResponse summaryResponse(
            UUID caseId,
            JenkinsValidationSummaryRequest request,
            String summary,
            boolean prCommentCreated,
            String prCommentUrl,
            boolean jiraCommentCreated,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new JenkinsValidationSummaryResponse(
                caseId,
                safe(request.defectKey()),
                safe(request.pullRequestId()),
                safe(request.pullRequestUrl()),
                normalizeValidationStatus(request.validationStatus()),
                safe(summary),
                prCommentCreated,
                safe(prCommentUrl),
                jiraCommentCreated,
                unique(blockers),
                sanitize(unique(warnings)),
                unique(nextActions),
                Instant.now()
        );
    }

    private String buildSummary(UUID caseId, JenkinsValidationSummaryRequest request) {
        String status = normalizeValidationStatus(request.validationStatus());
        List<String> lines = new ArrayList<>();
        lines.add("## ReplayLab Validation Summary");
        lines.add("");
        lines.add("### Defect");
        lines.add("");
        lines.add("- Defect: " + safe(request.defectKey()));
        lines.add("- Summary: " + safe(request.defectSummary()));
        lines.add("- ReplayLab case: " + caseId);
        lines.add("");
        lines.add("### Pull Request");
        lines.add("");
        lines.add("- PR: " + safe(request.pullRequestUrl()));
        lines.add("- Source branch: " + safe(request.sourceBranch()));
        lines.add("- Target branch: " + safe(request.targetBranch()));
        lines.add("");
        lines.add("### AGENTS Rule Preflight");
        lines.add("");
        lines.add("- Status: " + safe(request.agentsPreflightStatus()));
        lines.add("- Blocker violations: " + request.blockerViolationCount());
        lines.add("");
        lines.add("### Jenkins Validation");
        lines.add("");
        lines.add("- Job: " + safe(request.jenkinsJobName()));
        lines.add("- Status: " + status);
        lines.add("- Build URL: " + safe(request.jenkinsBuildUrl()));
        if ("FAILURE".equals(status)) {
            lines.add("- Action: PR should not be merged until the failure is reviewed.");
        }
        lines.add("");
        lines.add("### Applied Change");
        lines.add("");
        lines.add("- Changed files:");
        List<String> files = request.changedFiles() == null ? List.of() : request.changedFiles();
        if (files.isEmpty()) {
            lines.add("  - none listed");
        } else {
            files.forEach(file -> lines.add("  - " + safe(file)));
        }
        lines.add("");
        lines.add("### Guardrails");
        lines.add("");
        lines.add("ReplayLab did not:");
        lines.add("");
        lines.add("- merge the PR");
        lines.add("- push directly to the target branch");
        lines.add("- trigger deployment");
        lines.add("- expose credentials or production payloads");
        lines.add("");
        lines.add("### Next Actions");
        lines.add("");
        lines.add("- Reviewer should inspect the PR diff.");
        lines.add("- Merge only after human validation.");
        return safe(String.join("\n", lines));
    }

    private String buildSummaryFromStatus(
            ReplayCaseEntity replayCase,
            JenkinsValidationSummaryCommentRequest request
    ) {
        JenkinsValidationStatusResponse status = status(replayCase.getId());
        return buildSummary(
                replayCase.getId(),
                new JenkinsValidationSummaryRequest(
                        request.requestedBy(),
                        request.projectKey(),
                        request.repositorySlug(),
                        firstNonBlank(request.jiraKey(), status.defectKey(), replayCase.getJiraKey()),
                        "",
                        firstNonBlank(request.pullRequestId(), status.pullRequestId()),
                        request.pullRequestUrl(),
                        status.sourceBranch(),
                        status.targetBranch(),
                        status.jenkinsJobName(),
                        status.jenkinsBuildUrl(),
                        status.validationStatus(),
                        List.of(),
                        "",
                        0
                )
        );
    }

    private Optional<String> latestSummary(UUID caseId) {
        return latestEvidence(caseId, EvidenceType.JENKINS_VALIDATION_SUMMARY_PREVIEW)
                .map(EvidenceEntity::getContentText)
                .map(this::readJson)
                .map(node -> text(node, "summaryPreview"))
                .filter(value -> !value.isBlank());
    }

    private Optional<EvidenceEntity> latestEvidence(UUID caseId, EvidenceType... types) {
        List<EvidenceType> wanted = List.of(types);
        return evidenceRepository.findByCaseId(caseId).stream()
                .filter(evidence -> wanted.contains(evidence.getEvidenceType()))
                .max((left, right) -> {
                    Instant leftTime = left.getCreatedAt() == null ? Instant.EPOCH : left.getCreatedAt();
                    Instant rightTime = right.getCreatedAt() == null ? Instant.EPOCH : right.getCreatedAt();
                    return leftTime.compareTo(rightTime);
                });
    }

    private String finishedAt(String status, long timestampMillis, long durationMillis) {
        if (timestampMillis <= 0 || durationMillis <= 0) {
            return "";
        }
        return switch (normalizeValidationStatus(status)) {
            case "SUCCESS", "FAILURE", "UNSTABLE", "ABORTED" ->
                    Instant.ofEpochMilli(timestampMillis + durationMillis).toString();
            default -> "";
        };
    }

    private List<String> nextActionsForStatus(String status) {
        return switch (normalizeValidationStatus(status)) {
            case "SUCCESS" -> List.of("Comment validation summary on PR.");
            case "FAILURE", "UNSTABLE", "ABORTED" ->
                    List.of("Do not merge until Jenkins validation is reviewed.");
            case "RUNNING", "QUEUED" -> List.of("Refresh Jenkins validation result.");
            default -> List.of("Review Jenkins validation evidence.");
        };
    }

    private String normalizeValidationStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return switch (status.trim().toUpperCase()) {
            case "NOT_TRIGGERED" -> "NOT_TRIGGERED";
            case "QUEUED" -> "QUEUED";
            case "RUNNING", "BUILDING" -> "RUNNING";
            case "SUCCESS" -> "SUCCESS";
            case "FAILURE", "FAILED" -> "FAILURE";
            case "UNSTABLE" -> "UNSTABLE";
            case "ABORTED" -> "ABORTED";
            default -> "UNKNOWN";
        };
    }

    private List<String> previewBlockers(JenkinsValidationRequest request) {
        List<String> blockers = new ArrayList<>();
        if (isBlank(request.requestedBy())) blockers.add("REQUESTED_BY_REQUIRED");
        if (isBlank(request.defectKey())) blockers.add("DEFECT_KEY_REQUIRED");
        if (isBlank(request.sourceBranch())) blockers.add("SOURCE_BRANCH_REQUIRED");
        if (isBlank(request.targetBranch())) blockers.add("TARGET_BRANCH_REQUIRED");
        if (isBlank(request.pullRequestId())) blockers.add("PULL_REQUEST_ID_REQUIRED");
        return blockers;
    }

    private JenkinsValidationResponse response(
            UUID caseId,
            JenkinsValidationRequest request,
            boolean previewOnly,
            boolean triggered,
            String jobName,
            String queueUrl,
            String buildUrl,
            Map<String, String> parameters,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        return new JenkinsValidationResponse(
                caseId,
                safe(request.defectKey()),
                previewOnly,
                triggered,
                safe(jobName),
                safe(queueUrl),
                safe(buildUrl),
                safe(request.sourceBranch()),
                safe(request.targetBranch()),
                safe(request.pullRequestId()),
                sanitize(parameters),
                guardrails(),
                unique(blockers),
                sanitize(unique(warnings)),
                unique(nextActions),
                Instant.now()
        );
    }

    private Map<String, String> plannedParameters(
            UUID caseId,
            JenkinsValidationRequest request
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BRANCH", safe(request.sourceBranch()));
        values.put("PR_ID", safe(request.pullRequestId()));
        values.put("DEFECT_KEY", safe(request.defectKey()));
        values.put("VALIDATION_MODE", isBlank(request.validationType())
                ? "PR_BUILD"
                : safe(request.validationType()));
        values.put("REPLAYLAB_CASE_ID", caseId.toString());
        values.put("SKIP_DEPLOY", "true");
        return values;
    }

    private String resolveJobName(JenkinsValidationRequest request) {
        String type = repositoryType(request);
        if ("FRONTEND".equals(type)) {
            return safe(properties.getJenkins()
                    .getValidation()
                    .getFrontend()
                    .getDefaultJobName());
        }
        if ("BACKEND".equals(type)) {
            return safe(properties.getJenkins()
                    .getValidation()
                    .getBackend()
                    .getDefaultJobName());
        }
        return "";
    }

    private String repositoryType(JenkinsValidationRequest request) {
        String type = safe(request.repositoryType()).toUpperCase();
        if ("BACKEND".equals(type) || "FRONTEND".equals(type)) {
            return type;
        }
        String slug = safe(request.repositorySlug()).toLowerCase();
        if (slug.contains("backend")) return "BACKEND";
        if (slug.contains("frontend") || slug.contains("ui")) return "FRONTEND";
        return "UNKNOWN";
    }

    private List<String> guardrails() {
        return List.of(
                "HUMAN_APPROVAL_REQUIRED",
                "NO_AUTO_DEPLOY",
                "NO_AUTO_MERGE",
                "NO_TARGET_BRANCH_PUSH",
                "NO_SECRET_EXPOSURE"
        );
    }

    private JenkinsValidationRequest safe(JenkinsValidationRequest request) {
        if (request == null) {
            return new JenkinsValidationRequest(
                    "", "", "", "", "", "", "", "", "", "",
                    List.of(), "PR_BUILD", false, false
            );
        }
        return request;
    }

    private JenkinsValidationResultRefreshRequest safe(JenkinsValidationResultRefreshRequest request) {
        if (request == null) {
            return new JenkinsValidationResultRefreshRequest(
                    "", "", "", "", "", "", "", "", "", ""
            );
        }
        return request;
    }

    private JenkinsValidationSummaryRequest safe(JenkinsValidationSummaryRequest request) {
        if (request == null) {
            return new JenkinsValidationSummaryRequest(
                    "", "", "", "", "", "", "", "", "", "", "",
                    "UNKNOWN", List.of(), "", 0
            );
        }
        return request;
    }

    private JenkinsValidationSummaryCommentRequest safe(JenkinsValidationSummaryCommentRequest request) {
        if (request == null) {
            return new JenkinsValidationSummaryCommentRequest(
                    "", "", "", "", "", "", false, false, false, false
            );
        }
        return request;
    }

    private void saveEvidence(
            UUID caseId,
            EvidenceType type,
            JenkinsValidationResponse response
    ) {
        try {
            EvidenceEntity entity = new EvidenceEntity();
            entity.setCaseId(caseId);
            entity.setEvidenceType(type);
            entity.setSource(SOURCE);
            entity.setSanitized(true);
            entity.setContentText(objectMapper.writeValueAsString(response));
            evidenceRepository.save(entity);
        } catch (Exception ignored) {
            // Evidence persistence must not change guarded validation behavior.
        }
    }

    private void saveEvidence(
            UUID caseId,
            EvidenceType type,
            Object response
    ) {
        try {
            EvidenceEntity entity = new EvidenceEntity();
            entity.setCaseId(caseId);
            entity.setEvidenceType(type);
            entity.setSource(SOURCE);
            entity.setSanitized(true);
            entity.setContentText(objectMapper.writeValueAsString(response));
            evidenceRepository.save(entity);
        } catch (Exception ignored) {
            // Evidence persistence must not change guarded validation behavior.
        }
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private JsonNode readJson(String value) {
        try {
            if (value == null || value.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : safe(node.path(field).asText(""));
    }

    private boolean bool(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    private List<String> list(JsonNode node, String field) {
        if (node == null || !node.path(field).isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.path(field).forEach(item -> values.add(safe(item.asText(""))));
        return values;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return safe(value);
            }
        }
        return "";
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        return root == null || root.getMessage() == null ? "" : root.getMessage();
    }

    private Map<String, String> sanitize(Map<String, String> values) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (values == null) {
            return sanitized;
        }
        values.forEach((key, value) -> sanitized.put(safe(key), safe(value)));
        return sanitized;
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::safe).toList();
    }

    private List<String> unique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)bearer[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)cookie[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)token[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)password[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)secret[^\\s,;]*", "[redacted]")
                .replaceAll("https?://[^\\s]*@[^\\s]+", "[redacted-url]");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

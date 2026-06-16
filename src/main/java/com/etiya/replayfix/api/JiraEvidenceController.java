package com.etiya.replayfix.api;

import com.etiya.replayfix.model.ApprovalRequestView;
import com.etiya.replayfix.model.JiraEvidenceCommentPreview;
import com.etiya.replayfix.model.JiraEvidenceCommentPublishResult;
import com.etiya.replayfix.service.ApprovalService;
import com.etiya.replayfix.service.ApprovedJiraEvidenceCommentPublishService;
import com.etiya.replayfix.service.JiraEvidenceCommentPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/jira-evidence-summary")
public class JiraEvidenceController {

    private final JiraEvidenceCommentPreviewService previewService;
    private final ApprovalService approvalService;
    private final ApprovedJiraEvidenceCommentPublishService publishService;

    public JiraEvidenceController(
            JiraEvidenceCommentPreviewService previewService,
            ApprovalService approvalService,
            ApprovedJiraEvidenceCommentPublishService publishService
    ) {
        this.previewService = previewService;
        this.approvalService = approvalService;
        this.publishService = publishService;
    }

    @PostMapping("/preview")
    public ResponseEntity<JiraEvidenceCommentPreview> createPreview(@PathVariable UUID caseId) {
        try {
            JiraEvidenceCommentPreview preview = previewService.createPreview(caseId);
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/approval")
    public ResponseEntity<ApprovalRequestView> requestApproval(
            @PathVariable UUID caseId,
            @RequestBody JiraEvidenceSummaryApprovalRequest request
    ) {
        try {
            ApprovalRequestView approval = approvalService.createJiraEvidenceSummaryApproval(
                    caseId,
                    request.previewEvidenceId(),
                    request.actor(),
                    request.comment()
            );
            return ResponseEntity.ok(approval);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/publish")
    public ResponseEntity<JiraEvidenceCommentPublishResult> publish(
            @PathVariable UUID caseId,
            @RequestBody JiraEvidenceSummaryPublishRequest request
    ) {
        try {
            JiraEvidenceCommentPublishResult result = publishService.publish(
                    caseId,
                    request.previewEvidenceId(),
                    request.approvalId()
            );

            if (result.success()) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/latest-preview")
    public ResponseEntity<JiraEvidenceCommentPreview> getLatestPreview(@PathVariable UUID caseId) {
        try {
            JiraEvidenceCommentPreview preview = previewService.createPreview(caseId);
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record JiraEvidenceSummaryApprovalRequest(
            UUID previewEvidenceId,
            String actor,
            String comment
    ) {
    }

    public record JiraEvidenceSummaryPublishRequest(
            UUID previewEvidenceId,
            UUID approvalId
    ) {
    }
}

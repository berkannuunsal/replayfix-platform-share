package com.etiya.replayfix.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_case")
public class ReplayCaseEntity {
    @Id
    private UUID id;

    @Column(name = "jira_key", nullable = false, length = 100)
    private String jiraKey;

    @Column(name = "target_key", nullable = false, length = 150)
    private String targetKey;

    @Column(name = "order_id", length = 200)
    private String orderId;

    @Column(name = "trace_id", length = 200)
    private String traceId;

    @Column(name = "incident_time")
    private Instant incidentTime;

    @Column(name = "application_name", length = 150)
    private String applicationName;

    @Column(name = "environment", length = 50)
    private String environment;

    @Column(name = "synthetic", nullable = false)
    private boolean synthetic = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReplayCaseStatus status;

    @Column(name = "source_branch", length = 200)
    private String sourceBranch;

    @Column(name = "source_commit", length = 100)
    private String sourceCommit;

    @Column(name = "image_tag", length = 300)
    private String imageTag;

    @Column(name = "namespace", length = 200)
    private String namespace;

    @Column(name = "generated_branch", length = 300)
    private String generatedBranch;

    @Column(name = "pull_request_url", length = 1000)
    private String pullRequestUrl;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "jenkins_job_name", length = 300)
    private String jenkinsJobName;

    @Column(name = "jenkins_build_number")
    private Integer jenkinsBuildNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (status == null) status = ReplayCaseStatus.NEW;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public String getJiraKey() { return jiraKey; }
    public void setJiraKey(String value) { this.jiraKey = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { this.targetKey = value; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String value) { this.orderId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { this.traceId = value; }
    public Instant getIncidentTime() { return incidentTime; }
    public void setIncidentTime(Instant value) { this.incidentTime = value; }
    public ReplayCaseStatus getStatus() { return status; }
    public void setStatus(ReplayCaseStatus value) { this.status = value; }
    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String value) { this.sourceBranch = value; }
    public String getSourceCommit() { return sourceCommit; }
    public void setSourceCommit(String value) { this.sourceCommit = value; }
    public String getImageTag() { return imageTag; }
    public void setImageTag(String value) { this.imageTag = value; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String value) { this.namespace = value; }
    public String getGeneratedBranch() { return generatedBranch; }
    public void setGeneratedBranch(String value) { this.generatedBranch = value; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public void setPullRequestUrl(String value) { this.pullRequestUrl = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String value) { this.applicationName = value; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { this.environment = value; }
    public boolean isSynthetic() { return synthetic; }
    public void setSynthetic(boolean value) { this.synthetic = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public String getJenkinsJobName() { return jenkinsJobName; }
    public void setJenkinsJobName(String value) { this.jenkinsJobName = value; }
    public Integer getJenkinsBuildNumber() { return jenkinsBuildNumber; }
    public void setJenkinsBuildNumber(Integer value) { this.jenkinsBuildNumber = value; }
}

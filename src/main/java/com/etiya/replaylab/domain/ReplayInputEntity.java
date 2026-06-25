package com.etiya.replaylab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_replay_input")
public class ReplayInputEntity {
    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "jira_key", nullable = false, length = 100)
    private String jiraKey;

    @Column(name = "target_key", nullable = false, length = 150)
    private String targetKey;

    @Column(name = "endpoint_path", length = 1000)
    private String endpointPath;

    @Column(name = "http_method", length = 20)
    private String httpMethod;

    @Column(name = "sanitized_headers_json", columnDefinition = "text")
    private String sanitizedHeadersJson;

    @Column(name = "sanitized_request_body_json", columnDefinition = "text")
    private String sanitizedRequestBodyJson;

    @Column(name = "sanitized_query_params_json", columnDefinition = "text")
    private String sanitizedQueryParamsJson;

    @Column(name = "trace_id", length = 200)
    private String traceId;

    @Column(name = "order_id", length = 200)
    private String orderId;

    @Column(name = "customer_id", length = 200)
    private String customerId;

    @Column(name = "account_id", length = 200)
    private String accountId;

    @Column(name = "business_key", length = 300)
    private String businessKey;

    @Column(length = 50)
    private String source;

    @Column(nullable = false)
    private boolean sanitized;

    @Column(name = "contains_secrets", nullable = false)
    private boolean containsSecrets;

    @Column(name = "contains_personal_data", nullable = false)
    private boolean containsPersonalData;

    @Column(name = "sanitization_warnings_json", columnDefinition = "text")
    private String sanitizationWarningsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public String getJiraKey() { return jiraKey; }
    public void setJiraKey(String value) { this.jiraKey = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { this.targetKey = value; }
    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String value) { this.endpointPath = value; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String value) { this.httpMethod = value; }
    public String getSanitizedHeadersJson() { return sanitizedHeadersJson; }
    public void setSanitizedHeadersJson(String value) { this.sanitizedHeadersJson = value; }
    public String getSanitizedRequestBodyJson() { return sanitizedRequestBodyJson; }
    public void setSanitizedRequestBodyJson(String value) { this.sanitizedRequestBodyJson = value; }
    public String getSanitizedQueryParamsJson() { return sanitizedQueryParamsJson; }
    public void setSanitizedQueryParamsJson(String value) { this.sanitizedQueryParamsJson = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { this.traceId = value; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String value) { this.orderId = value; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String value) { this.customerId = value; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String value) { this.accountId = value; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String value) { this.businessKey = value; }
    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }
    public boolean isSanitized() { return sanitized; }
    public void setSanitized(boolean value) { this.sanitized = value; }
    public boolean isContainsSecrets() { return containsSecrets; }
    public void setContainsSecrets(boolean value) { this.containsSecrets = value; }
    public boolean isContainsPersonalData() { return containsPersonalData; }
    public void setContainsPersonalData(boolean value) { this.containsPersonalData = value; }
    public String getSanitizationWarningsJson() { return sanitizationWarningsJson; }
    public void setSanitizationWarningsJson(String value) { this.sanitizationWarningsJson = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

// ReplayFix Incident Dashboard - Main Application
(function() {
    'use strict';

    // State
    const state = {
        currentCaseId: null,
        dashboard: null,
        autoRefresh: false,
        refreshInterval: null,
        refreshDelay: 30000
    };

    // API Client
    const api = {
        baseUrl: '/api/v1',
        
        async request(endpoint, options = {}) {
            const timeout = options.timeout || 30000;
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), timeout);
            
            try {
                const response = await fetch(this.baseUrl + endpoint, {
                    ...options,
                    signal: controller.signal,
                    headers: {
                        'Content-Type': 'application/json',
                        ...options.headers
                    }
                });
                
                clearTimeout(timeoutId);
                
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                
                const contentType = response.headers.get('content-type');
                if (contentType && contentType.includes('application/json')) {
                    return await response.json();
                }
                
                return await response.text();
            } catch (error) {
                clearTimeout(timeoutId);
                if (error.name === 'AbortError') {
                    throw new Error('Request timeout');
                }
                throw error;
            }
        },
        
        async getCaseList(query, status, limit = 20) {
            const params = new URLSearchParams();
            if (query) params.append('query', query);
            if (status) params.append('status', status);
            params.append('limit', limit);
            return this.request(`/dashboard/cases?${params}`);
        },
        
        async getCaseDashboard(caseId) {
            return this.request(`/dashboard/cases/${caseId}`);
        },
        
        async getWorkflow(workflowId) {
            return this.request(`/workflows/${workflowId}`);
        },
        
        async requestApproval(caseId, previewEvidenceId, actor, comment) {
            return this.request(`/cases/${caseId}/jira-evidence-summary/approval`, {
                method: 'POST',
                body: JSON.stringify({ previewEvidenceId, actor, comment })
            });
        },
        
        async approveRequest(approvalId, actor, comment) {
            return this.request(`/approvals/${approvalId}/approve`, {
                method: 'POST',
                body: JSON.stringify({ actor, comment })
            });
        },
        
        async rejectRequest(approvalId, actor, comment) {
            return this.request(`/approvals/${approvalId}/reject`, {
                method: 'POST',
                body: JSON.stringify({ actor, comment })
            });
        },
        
        async publishToJira(caseId, previewEvidenceId, approvalId) {
            return this.request(`/cases/${caseId}/jira-evidence-summary/publish`, {
                method: 'POST',
                body: JSON.stringify({ previewEvidenceId, approvalId })
            });
        }
    };

    // Router
    const router = {
        init() {
            this.handleRoute();
            window.addEventListener('popstate', () => this.handleRoute());
        },
        
        handleRoute() {
            const params = new URLSearchParams(window.location.search);
            const caseId = params.get('caseId');
            
            if (caseId) {
                state.currentCaseId = caseId;
                showView('dashboard');
                loadDashboard(caseId);
            } else {
                showView('caseList');
                loadCaseList();
            }
        },
        
        navigate(caseId) {
            const url = caseId ? `?caseId=${caseId}` : '';
            window.history.pushState({}, '', window.location.pathname + url);
            this.handleRoute();
        }
    };

    // UI Rendering
    function showView(viewName) {
        document.getElementById('caseListView').classList.add('hidden');
        document.getElementById('dashboardView').classList.add('hidden');
        
        if (viewName === 'caseList') {
            document.getElementById('caseListView').classList.remove('hidden');
        } else if (viewName === 'dashboard') {
            document.getElementById('dashboardView').classList.remove('hidden');
        }
    }

    async function loadCaseList() {
        showLoading(true);
        try {
            const cases = await api.getCaseList();
            renderCaseList(cases);
        } catch (error) {
            showToast('Failed to load case list: ' + error.message, 'error');
        } finally {
            showLoading(false);
        }
    }

    function renderCaseList(cases) {
        const container = document.getElementById('caseListContainer');
        
        if (!cases || cases.length === 0) {
            container.innerHTML = '<p class="text-center" style="color: var(--text-secondary); padding: 2rem;">No cases found</p>';
            return;
        }
        
        container.innerHTML = cases.map(c => `
            <div class="case-item" data-case-id="${c.caseId}">
                <div class="case-item-header">
                    <span class="case-jira-key">${escapeHtml(c.jiraKey)}</span>
                    <span class="badge badge-${getStatusClass(c.workflowStatus)}">${escapeHtml(c.workflowStatus)}</span>
                </div>
                <div class="case-summary">${escapeHtml(c.summary || '')}</div>
                <div class="case-meta" style="margin-top: 0.75rem; font-size: 0.875rem; color: var(--text-secondary);">
                    <span>${formatDate(c.createdAt)}</span>
                    ${c.rootCauseConfidence ? `<span>Confidence: ${Math.round(c.rootCauseConfidence * 100)}%</span>` : ''}
                </div>
            </div>
        `).join('');
        
        container.querySelectorAll('.case-item').forEach(item => {
            item.addEventListener('click', () => {
                const caseId = item.dataset.caseId;
                router.navigate(caseId);
            });
        });
    }

    async function loadDashboard(caseId) {
        showLoading(true);
        try {
            const dashboard = await api.getCaseDashboard(caseId);
            state.dashboard = dashboard;
            renderDashboard(dashboard);
            updateNavInfo(caseId);
        } catch (error) {
            showToast('Failed to load dashboard: ' + error.message, 'error');
        } finally {
            showLoading(false);
        }
    }

    function renderDashboard(dashboard) {
        renderIncidentHeader(dashboard.caseSummary, dashboard.workflow);
        renderWorkflowProgress(dashboard.workflow);
        renderEvidenceMatrix(dashboard.evidenceCards);
        renderRootCause(dashboard.rootCause);
        renderRovoRca(dashboard.rovoRca);
        renderMissingEvidence(dashboard.missingEvidence);
        renderJiraPreview(dashboard.jiraPreview, dashboard.policies);
        renderApprovals(dashboard.approvals);
        renderAuditTimeline(dashboard.auditEvents);
        updatePublishButton(dashboard.jiraPreview, dashboard.approvals, dashboard.policies);
    }

    function renderIncidentHeader(caseSummary, workflow) {
        const jiraKeyEl = document.getElementById('jiraKey');
        const summaryEl = document.getElementById('issueSummary');
        const statusEl = document.getElementById('issueStatus');
        const workflowStatusEl = document.getElementById('workflowStatus');
        const alertEl = document.getElementById('workflowAlert');
        
        jiraKeyEl.textContent = caseSummary.jiraKey;
        summaryEl.textContent = caseSummary.summary;
        statusEl.textContent = caseSummary.status;
        statusEl.className = 'badge badge-' + getStatusClass(caseSummary.status);
        
        if (workflow) {
            workflowStatusEl.textContent = workflow.status;
            workflowStatusEl.className = 'badge badge-' + getStatusClass(workflow.status);
            
            if (workflow.status === 'FAILED') {
                alertEl.textContent = '⚠️ One or more required evidence steps failed.';
                alertEl.className = 'alert alert-danger';
                alertEl.classList.remove('hidden');
            } else if (workflow.status === 'PARTIAL_SUCCESS') {
                alertEl.textContent = '⚠️ Analysis completed with missing optional evidence.';
                alertEl.className = 'alert alert-warning';
                alertEl.classList.remove('hidden');
            } else {
                alertEl.classList.add('hidden');
            }
        } else {
            workflowStatusEl.textContent = 'NO_WORKFLOW';
            workflowStatusEl.className = 'badge badge-muted';
        }
        
        document.getElementById('openJiraBtn').onclick = () => {
            window.open(`https://your-jira.atlassian.net/browse/${caseSummary.jiraKey}`, '_blank');
        };
    }

    function renderWorkflowProgress(workflow) {
        const container = document.getElementById('workflowTimeline');
        const summaryEl = document.getElementById('progressSummary');
        
        if (!workflow || !workflow.steps) {
            container.innerHTML = '<p style="color: var(--text-secondary);">No workflow data available</p>';
            summaryEl.textContent = '';
            return;
        }
        
        summaryEl.textContent = `${workflow.successfulStepCount} succeeded, ${workflow.failedStepCount} failed, ${workflow.skippedStepCount} skipped`;
        
        container.innerHTML = workflow.steps.map(step => `
            <div class="timeline-step status-${step.status.toLowerCase()}">
                <div class="step-icon">${getStepIcon(step.status)}</div>
                <div class="step-content">
                    <div class="step-name">${escapeHtml(step.stepName)}</div>
                    <div class="step-details">
                        Status: ${escapeHtml(step.status)}
                        ${step.resultSummary ? ` | ${escapeHtml(step.resultSummary)}` : ''}
                        ${step.errorMessage ? ` | Error: ${escapeHtml(step.errorMessage)}` : ''}
                    </div>
                </div>
                <span class="badge badge-${getStatusClass(step.status)}">${escapeHtml(step.status)}</span>
            </div>
        `).join('');
        
        adjustRefreshRate(workflow.status);
    }

    function renderEvidenceMatrix(cards) {
        const container = document.getElementById('evidenceCards');
        
        if (!cards || cards.length === 0) {
            container.innerHTML = '<p style="color: var(--text-secondary);">No evidence collected yet</p>';
            return;
        }
        
        container.innerHTML = cards.map(card => `
            <div class="evidence-card">
                <div class="evidence-card-header">
                    <span class="evidence-source">${escapeHtml(card.source)}</span>
                    <span class="badge badge-${getStatusClass(card.status)}">${escapeHtml(card.status)}</span>
                </div>
                <div class="evidence-finding">${escapeHtml(card.keyFinding)}</div>
                <div class="evidence-meta">
                    <span>Count: ${card.evidenceCount}</span>
                    <span>${card.confidence}</span>
                </div>
                ${card.lastCollectedAt ? `<div style="font-size: 0.75rem; color: var(--text-secondary); margin-top: 0.5rem;">Last: ${formatDate(card.lastCollectedAt)}</div>` : ''}
            </div>
        `).join('');
    }

    function renderRootCause(rootCause) {
        if (!rootCause) return;
        
        document.getElementById('probableRootCause').textContent = rootCause.probableRootCause;
        document.getElementById('confidencePercent').textContent = Math.round(rootCause.confidence * 100) + '%';
        
        const bandEl = document.getElementById('confidenceBand');
        bandEl.textContent = rootCause.confidenceBand;
        bandEl.className = 'badge badge-' + getConfidenceClass(rootCause.confidenceBand);
        
        const analysisTypeEl = document.getElementById('analysisType');
        analysisTypeEl.textContent = rootCause.analysisType;
        analysisTypeEl.className = 'badge badge-primary';
        
        const fixSection = document.getElementById('fixDirectionSection');
        if (rootCause.recommendedFixDirection && rootCause.recommendedFixDirection.length > 0) {
            const listEl = document.getElementById('fixDirectionList');
            listEl.innerHTML = rootCause.recommendedFixDirection
                .map(item => `<li>${escapeHtml(item)}</li>`)
                .join('');
            fixSection.classList.remove('hidden');
        } else {
            fixSection.classList.add('hidden');
        }
    }

    function renderRovoRca(rovoRca) {
        const section = document.getElementById('rovoRcaSection');
        if (!section) return;

        if (!rovoRca || rovoRca.importStatus !== 'IMPORTED') {
            section.classList.add('hidden');
            return;
        }

        section.classList.remove('hidden');

        const importStatusEl = document.getElementById('rovoRcaImportStatus');
        importStatusEl.textContent = rovoRca.importStatus || 'UNKNOWN';
        importStatusEl.className = 'badge badge-' + getStatusClass(rovoRca.importStatus);

        const rcaStatusEl = document.getElementById('rovoRcaStatusValue');
        rcaStatusEl.textContent = rovoRca.rcaStatus || 'HYPOTHESIS';
        rcaStatusEl.className = 'badge badge-' + getStatusClass(rovoRca.rcaStatus || 'HYPOTHESIS');

        const confidence = typeof rovoRca.confidence === 'number'
            ? `${Math.round(rovoRca.confidence * 100)}%`
            : 'N/A';
        document.getElementById('rovoRcaConfidence').textContent = confidence;
        document.getElementById('rovoRcaProbableRootCause').textContent = rovoRca.probableRootCause || 'N/A';
        document.getElementById('rovoRcaHumanReport').textContent = rovoRca.rawHumanReport || '';

        const warningsSection = document.getElementById('rovoRcaWarningsSection');
        const warningsList = document.getElementById('rovoRcaWarnings');
        if (rovoRca.normalizationWarnings && rovoRca.normalizationWarnings.length > 0) {
            warningsSection.classList.remove('hidden');
            warningsList.innerHTML = rovoRca.normalizationWarnings
                .map(item => `<li>${escapeHtml(item)}</li>`)
                .join('');
        } else {
            warningsSection.classList.add('hidden');
            warningsList.innerHTML = '';
        }

        document.getElementById('rovoRcaNormalizedJson').textContent =
            JSON.stringify(rovoRca.normalizedRovoJson || {}, null, 2);
        document.getElementById('rovoRcaRawJson').textContent =
            JSON.stringify(rovoRca.rawRovoJson || {}, null, 2);
    }

    function renderMissingEvidence(missingList) {
        const section = document.getElementById('missingEvidenceSection');
        const listEl = document.getElementById('missingEvidenceList');
        
        if (!missingList || missingList.length === 0) {
            section.classList.add('hidden');
            return;
        }
        
        section.classList.remove('hidden');
        listEl.innerHTML = missingList.map(item => `
            <li class="missing-item" style="margin-bottom: 0.75rem;">
                <strong>${escapeHtml(item.evidenceType)}</strong>
                <span class="badge badge-${item.severity === 'HIGH' ? 'danger' : 'warning'}" style="margin-left: 0.5rem;">${escapeHtml(item.severity)}</span>
                <div style="color: var(--text-secondary); font-size: 0.875rem; margin-top: 0.25rem;">
                    ${escapeHtml(item.reason)}
                </div>
            </li>
        `).join('');
    }

    function renderJiraPreview(preview, policies) {
        const section = document.getElementById('jiraPreviewSection');
        const metaEl = document.getElementById('previewMeta');
        const textEl = document.getElementById('previewText');
        
        if (!preview) {
            section.classList.add('hidden');
            return;
        }
        
        section.classList.remove('hidden');
        
        metaEl.innerHTML = `
            <span><strong>Preview ID:</strong> ${preview.previewEvidenceId.substring(0, 8)}</span>
            <span><strong>Size:</strong> ${preview.plainTextLength} chars</span>
            <span><strong>Sanitized:</strong> ${preview.sanitized ? '✓' : '✗'}</span>
            <span><strong>Published:</strong> ${preview.published ? '✓' : '✗'}</span>
        `;
        
        textEl.textContent = preview.plainTextPreview;
        
        setupPreviewActions(preview, policies);
    }

    function setupPreviewActions(preview, policies) {
        document.getElementById('copyPreviewBtn').onclick = () => {
            navigator.clipboard.writeText(preview.plainTextPreview)
                .then(() => showToast('Preview copied to clipboard', 'success'))
                .catch(() => showToast('Failed to copy', 'error'));
        };
        
        document.getElementById('requestApprovalBtn').onclick = () => {
            showApprovalModal(preview.previewEvidenceId);
        };
        
        document.getElementById('copyPromptBtn').onclick = () => {
            const prompt = document.getElementById('rovoPromptText').value;
            navigator.clipboard.writeText(prompt)
                .then(() => showToast('Prompt copied to clipboard', 'success'))
                .catch(() => showToast('Failed to copy', 'error'));
        };
    }

    function updatePublishButton(preview, approvals, policies) {
        const publishBtn = document.getElementById('publishBtn');
        
        if (!preview || !policies) {
            publishBtn.disabled = true;
            return;
        }
        
        const hasApprovedSummary = approvals && approvals.some(a => 
            a.targetType === 'JIRA_EVIDENCE_SUMMARY' && a.status === 'APPROVED'
        );
        
        const canPublish = preview && !preview.published && hasApprovedSummary && policies.allowJiraCommentWrite;
        
        publishBtn.disabled = !canPublish;
        
        if (canPublish) {
            publishBtn.onclick = () => {
                showPublishConfirmation(preview.previewEvidenceId, approvals);
            };
        }
    }

    function renderApprovals(approvals) {
        const container = document.getElementById('approvalsList');
        
        if (!approvals || approvals.length === 0) {
            container.innerHTML = '<p style="color: var(--text-secondary);">No approvals requested yet</p>';
            return;
        }
        
        container.innerHTML = approvals.map(approval => `
            <div class="approval-item">
                <div class="approval-header">
                    <span class="approval-type">${escapeHtml(approval.targetType)}</span>
                    <span class="badge badge-${getStatusClass(approval.status)}">${escapeHtml(approval.status)}</span>
                </div>
                <div class="approval-details">
                    <div>Requested by: ${escapeHtml(approval.requestedBy)} on ${formatDate(approval.requestedAt)}</div>
                    ${approval.requestComment ? `<div>Request: ${escapeHtml(approval.requestComment)}</div>` : ''}
                    ${approval.decidedBy ? `<div>Decided by: ${escapeHtml(approval.decidedBy)} on ${formatDate(approval.decidedAt)}</div>` : ''}
                    ${approval.decisionComment ? `<div>Decision: ${escapeHtml(approval.decisionComment)}</div>` : ''}
                </div>
                ${approval.status === 'PENDING' ? `
                    <div style="margin-top: 0.75rem; display: flex; gap: 0.5rem;">
                        <button class="btn btn-success btn-sm" onclick="handleApprove('${approval.id}')">✓ Approve</button>
                        <button class="btn btn-danger btn-sm" onclick="handleReject('${approval.id}')">✗ Reject</button>
                    </div>
                ` : ''}
            </div>
        `).join('');
    }

    function renderAuditTimeline(events) {
        const container = document.getElementById('auditTimeline');
        
        if (!events || events.length === 0) {
            container.innerHTML = '<p style="color: var(--text-secondary);">No audit events</p>';
            return;
        }
        
        container.innerHTML = events.map(event => `
            <div class="audit-event">
                <div class="audit-time">${formatDateTime(event.createdAt)}</div>
                <div class="audit-content">
                    <div class="audit-action">${escapeHtml(event.action)}</div>
                    <div class="audit-details">
                        by ${escapeHtml(event.actor)}
                        ${event.details ? ` | ${escapeHtml(event.details)}` : ''}
                    </div>
                </div>
            </div>
        `).join('');
    }

    // Event Handlers
    window.handleApprove = async function(approvalId) {
        showDecisionModal(approvalId, 'approve');
    };

    window.handleReject = async function(approvalId) {
        showDecisionModal(approvalId, 'reject');
    };

    function showApprovalModal(previewEvidenceId) {
        const modal = document.getElementById('modal');
        document.getElementById('modalTitle').textContent = 'Request Approval';
        document.getElementById('modalBody').innerHTML = `
            <div style="margin-bottom: 1rem;">
                <label style="display: block; margin-bottom: 0.5rem;">Actor (your name)</label>
                <input type="text" id="approvalActor" class="form-input" style="width: 100%; padding: 0.5rem; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius); color: var(--text-primary);">
            </div>
            <div>
                <label style="display: block; margin-bottom: 0.5rem;">Comment (optional)</label>
                <textarea id="approvalComment" rows="3" class="form-input" style="width: 100%; padding: 0.5rem; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius); color: var(--text-primary);"></textarea>
            </div>
        `;
        document.getElementById('modalFooter').innerHTML = `
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-primary" onclick="submitApproval('${previewEvidenceId}')">Submit</button>
        `;
        modal.classList.remove('hidden');
    }

    function showDecisionModal(approvalId, action) {
        const modal = document.getElementById('modal');
        document.getElementById('modalTitle').textContent = action === 'approve' ? 'Approve Request' : 'Reject Request';
        document.getElementById('modalBody').innerHTML = `
            <div style="margin-bottom: 1rem;">
                <label style="display: block; margin-bottom: 0.5rem;">Actor (your name)</label>
                <input type="text" id="decisionActor" class="form-input" style="width: 100%; padding: 0.5rem; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius); color: var(--text-primary);">
            </div>
            <div>
                <label style="display: block; margin-bottom: 0.5rem;">Comment</label>
                <textarea id="decisionComment" rows="3" class="form-input" style="width: 100%; padding: 0.5rem; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius); color: var(--text-primary);"></textarea>
            </div>
        `;
        document.getElementById('modalFooter').innerHTML = `
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-${action === 'approve' ? 'success' : 'danger'}" onclick="submitDecision('${approvalId}', '${action}')">
                ${action === 'approve' ? '✓ Approve' : '✗ Reject'}
            </button>
        `;
        modal.classList.remove('hidden');
    }

    function showPublishConfirmation(previewEvidenceId, approvals) {
        const approval = approvals.find(a => a.targetType === 'JIRA_EVIDENCE_SUMMARY' && a.status === 'APPROVED');
        
        const modal = document.getElementById('modal');
        document.getElementById('modalTitle').textContent = 'Publish to Jira';
        document.getElementById('modalBody').innerHTML = `
            <p style="margin-bottom: 1rem;">Are you sure you want to publish this evidence snapshot as a Jira comment?</p>
            <p style="color: var(--text-secondary); font-size: 0.875rem;">This action will write to the Jira issue and cannot be undone.</p>
        `;
        document.getElementById('modalFooter').innerHTML = `
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-success" onclick="submitPublish('${previewEvidenceId}', '${approval.id}')">🚀 Publish</button>
        `;
        modal.classList.remove('hidden');
    }

    window.submitApproval = async function(previewEvidenceId) {
        const actor = document.getElementById('approvalActor').value.trim();
        const comment = document.getElementById('approvalComment').value.trim();
        
        if (!actor) {
            showToast('Actor name is required', 'error');
            return;
        }
        
        closeModal();
        showLoading(true);
        
        try {
            await api.requestApproval(state.currentCaseId, previewEvidenceId, actor, comment);
            showToast('Approval requested successfully', 'success');
            loadDashboard(state.currentCaseId);
        } catch (error) {
            showToast('Failed to request approval: ' + error.message, 'error');
        } finally {
            showLoading(false);
        }
    };

    window.submitDecision = async function(approvalId, action) {
        const actor = document.getElementById('decisionActor').value.trim();
        const comment = document.getElementById('decisionComment').value.trim();
        
        if (!actor) {
            showToast('Actor name is required', 'error');
            return;
        }
        
        closeModal();
        showLoading(true);
        
        try {
            if (action === 'approve') {
                await api.approveRequest(approvalId, actor, comment);
                showToast('Request approved successfully', 'success');
            } else {
                await api.rejectRequest(approvalId, actor, comment);
                showToast('Request rejected successfully', 'success');
            }
            loadDashboard(state.currentCaseId);
        } catch (error) {
            showToast(`Failed to ${action} request: ${error.message}`, 'error');
        } finally {
            showLoading(false);
        }
    };

    window.submitPublish = async function(previewEvidenceId, approvalId) {
        closeModal();
        showLoading(true);
        
        try {
            const result = await api.publishToJira(state.currentCaseId, previewEvidenceId, approvalId);
            if (result.success) {
                showToast('Evidence snapshot published to Jira successfully!', 'success');
            } else {
                showToast('Publish failed: ' + (result.errorMessage || 'Unknown error'), 'error');
            }
            loadDashboard(state.currentCaseId);
        } catch (error) {
            showToast('Failed to publish: ' + error.message, 'error');
        } finally {
            showLoading(false);
        }
    };

    window.closeModal = function() {
        document.getElementById('modal').classList.add('hidden');
    };

    // Utility Functions
    function showLoading(show) {
        document.getElementById('loadingOverlay').classList.toggle('hidden', !show);
    }

    function showToast(message, type = 'success') {
        const container = document.getElementById('toastContainer');
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;
        container.appendChild(toast);
        
        setTimeout(() => {
            toast.remove();
        }, 5000);
    }

    function updateNavInfo(caseId) {
        document.getElementById('caseIdDisplay').textContent = `Case: ${caseId.substring(0, 8)}...`;
        document.getElementById('lastRefresh').textContent = `Updated: ${new Date().toLocaleTimeString()}`;
    }

    function adjustRefreshRate(workflowStatus) {
        if (workflowStatus === 'RUNNING') {
            state.refreshDelay = 3000;
        } else if (workflowStatus === 'RETRY_WAITING') {
            state.refreshDelay = 10000;
        } else {
            state.refreshDelay = 30000;
        }
        
        if (state.autoRefresh) {
            startAutoRefresh();
        }
    }

    function startAutoRefresh() {
        if (state.refreshInterval) {
            clearInterval(state.refreshInterval);
        }
        
        state.refreshInterval = setInterval(() => {
            if (state.currentCaseId && !document.hidden) {
                loadDashboard(state.currentCaseId);
            }
        }, state.refreshDelay);
    }

    function stopAutoRefresh() {
        if (state.refreshInterval) {
            clearInterval(state.refreshInterval);
            state.refreshInterval = null;
        }
    }

    function getStatusClass(status) {
        const statusMap = {
            'SUCCESS': 'success',
            'RUNNING': 'primary',
            'PENDING': 'muted',
            'FAILED': 'danger',
            'SKIPPED': 'muted',
            'RETRY_WAITING': 'warning',
            'PARTIAL_SUCCESS': 'warning',
            'APPROVED': 'success',
            'REJECTED': 'danger',
            'CONFIRMED': 'success',
            'PROBABLE': 'warning',
            'UNAVAILABLE': 'muted',
            'IMPORTED': 'success',
            'DUPLICATE': 'warning',
            'INVALID_JSON': 'danger',
            'NOT_FOUND': 'muted',
            'HYPOTHESIS': 'warning'
        };
        return statusMap[status] || 'muted';
    }

    function getConfidenceClass(band) {
        const map = {
            'VERY HIGH': 'success',
            'HIGH': 'success',
            'MEDIUM': 'warning',
            'LOW': 'danger'
        };
        return map[band] || 'muted';
    }

    function getStepIcon(status) {
        const icons = {
            'SUCCESS': '✓',
            'RUNNING': '⟳',
            'PENDING': '○',
            'FAILED': '✗',
            'SKIPPED': '−',
            'RETRY_WAITING': '⏱'
        };
        return icons[status] || '○';
    }

    function formatDate(dateString) {
        if (!dateString) return 'N/A';
        try {
            return new Date(dateString).toLocaleDateString();
        } catch {
            return 'Invalid date';
        }
    }

    function formatDateTime(dateString) {
        if (!dateString) return 'N/A';
        try {
            return new Date(dateString).toLocaleString();
        } catch {
            return 'Invalid date';
        }
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Event Listeners
    document.getElementById('refreshBtn').addEventListener('click', () => {
        if (state.currentCaseId) {
            loadDashboard(state.currentCaseId);
        } else {
            loadCaseList();
        }
    });

    document.getElementById('autoRefreshToggle').addEventListener('change', (e) => {
        state.autoRefresh = e.target.checked;
        if (state.autoRefresh) {
            startAutoRefresh();
            showToast('Auto-refresh enabled', 'success');
        } else {
            stopAutoRefresh();
            showToast('Auto-refresh disabled', 'success');
        }
    });

    document.querySelector('.modal-close').addEventListener('click', closeModal);
    
    document.getElementById('modal').addEventListener('click', (e) => {
        if (e.target.id === 'modal') {
            closeModal();
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeModal();
        }
    });

    document.getElementById('caseSearch').addEventListener('input', (e) => {
        // Simple client-side filtering - would call API in production
        const query = e.target.value.toLowerCase();
        document.querySelectorAll('.case-item').forEach(item => {
            const text = item.textContent.toLowerCase();
            item.style.display = text.includes(query) ? '' : 'none';
        });
    });

    // Notification Center
    async function loadNotifications() {
        try {
            const response = await fetch(`${API_BASE}/notifications?status=UNREAD&limit=20`);
            if (!response.ok) return;
            
            const notifications = await response.json();
            const count = notifications.length;
            
            const badge = document.getElementById('notificationCount');
            const total = document.getElementById('notificationTotal');
            const list = document.getElementById('notificationList');
            
            if (count > 0) {
                badge.textContent = count;
                badge.classList.remove('hidden');
            } else {
                badge.classList.add('hidden');
            }
            
            total.textContent = count;
            
            if (count === 0) {
                list.innerHTML = '<div class="notification-empty">No new notifications</div>';
            } else {
                list.innerHTML = notifications.map(n => `
                    <div class="notification-item unread" data-id="${n.id}" data-url="${escapeHtml(n.targetUrl || '')}">
                        <div class="notification-title">${escapeHtml(n.title)}</div>
                        ${n.message ? `<div class="notification-message">${escapeHtml(n.message)}</div>` : ''}
                        <div class="notification-meta">
                            <span class="badge badge-${n.severity?.toLowerCase() || 'info'}">${escapeHtml(n.type)}</span>
                            <span>${formatDate(n.createdAt)}</span>
                        </div>
                    </div>
                `).join('');
                
                document.querySelectorAll('.notification-item').forEach(item => {
                    item.addEventListener('click', () => handleNotificationClick(
                        item.dataset.id, 
                        item.dataset.url
                    ));
                });
            }
        } catch (error) {
            console.error('Failed to load notifications:', error);
        }
    }
    
    async function handleNotificationClick(id, url) {
        try {
            await fetch(`${API_BASE}/notifications/${id}/read`, { method: 'POST' });
            toggleNotificationDropdown();
            if (url && url.startsWith('/replayfix/')) {
                window.location.href = url;
            }
            loadNotifications();
        } catch (error) {
            console.error('Failed to mark notification as read:', error);
        }
    }
    
    function toggleNotificationDropdown() {
        const dropdown = document.getElementById('notificationDropdown');
        dropdown.classList.toggle('hidden');
    }
    
    document.getElementById('notificationBtn').addEventListener('click', (e) => {
        e.stopPropagation();
        toggleNotificationDropdown();
        if (!document.getElementById('notificationDropdown').classList.contains('hidden')) {
            loadNotifications();
        }
    });
    
    document.addEventListener('click', () => {
        document.getElementById('notificationDropdown').classList.add('hidden');
    });
    
    document.getElementById('notificationDropdown').addEventListener('click', (e) => {
        e.stopPropagation();
    });
    
    // Poll notifications
    setInterval(() => {
        if (!document.hidden) {
            loadNotifications();
        }
    }, 30000);

    // Visibility change handling
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && state.autoRefresh && state.currentCaseId) {
            loadDashboard(state.currentCaseId);
        }
        if (!document.hidden) {
            loadNotifications();
        }
    });

    // Initialize
    router.init();
    loadNotifications();
})();

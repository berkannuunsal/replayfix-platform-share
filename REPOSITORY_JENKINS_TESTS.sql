-- Repository Resolution & Jenkins Integration Test Queries

-- ========================================
-- Test 1: Verify Canonical Evidence Fields
-- ========================================
SELECT 
    'Canonical Fields Test' AS test_name,
    id,
    confidence,
    content_text::json->>'projectKey' AS project_key,
    content_text::json->>'primaryRepositorySlug' AS primary_slug,
    content_text::json->>'sourceBranch' AS source_branch,
    content_text::json->>'repositoryState' AS state,
    content_text::json->>'primary' AS is_primary
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected:
-- project_key | primary_slug | source_branch | state     | is_primary
-- DCE         | backend      | test2         | AVAILABLE | true


-- ========================================
-- Test 2: Verify Backward Compatibility Fields
-- ========================================
SELECT 
    'Backward Compatibility Test' AS test_name,
    id,
    content_text::json->>'bitbucketProjectKey' AS bitbucket_project,
    content_text::json->>'repositorySlug' AS repo_slug,
    content_text::json->>'repositoryName' AS repo_name,
    content_text::json->>'cloneUrl' AS clone_url
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected:
-- bitbucket_project | repo_slug | repo_name | clone_url
-- DCE               | backend   | backend   | https://bitbucket.../backend.git


-- ========================================
-- Test 3: No Credential Leak
-- ========================================
SELECT 
    'Credential Leak Test' AS test_name,
    id,
    CASE 
        WHEN content_text LIKE '%password%' THEN 'FAILED: password found'
        WHEN content_text LIKE '%token%' THEN 'FAILED: token found'
        WHEN content_text LIKE '%secret%' THEN 'FAILED: secret found'
        WHEN content_text LIKE '%credential%' THEN 'FAILED: credential found'
        WHEN content_text ~ '://[^@]+:[^@]+@' THEN 'FAILED: user:pass pattern found'
        ELSE 'PASSED'
    END AS credential_check
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected: credential_check = 'PASSED'


-- ========================================
-- Test 4: Complete Evidence Structure
-- ========================================
SELECT 
    'Full Evidence Structure' AS test_name,
    id,
    confidence,
    content_text
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Manually verify JSON structure contains all required fields


-- ========================================
-- Test 5: Jenkins Evidence Created
-- ========================================
SELECT 
    'Jenkins Evidence Test' AS test_name,
    e.id,
    e.evidence_type,
    e.source,
    e.confidence,
    e.created_at
FROM rf_evidence e
WHERE e.case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND e.evidence_type IN ('JENKINS_BUILD', 'JENKINS_BUILD_CONTEXT')
ORDER BY e.created_at;

-- Expected: At least 1 row returned


-- ========================================
-- Test 6: All Evidence for Case
-- ========================================
SELECT 
    'All Evidence Summary' AS test_name,
    evidence_type,
    COUNT(*) AS count,
    MAX(confidence) AS max_confidence,
    MIN(created_at) AS first_created,
    MAX(created_at) AS last_created
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
GROUP BY evidence_type
ORDER BY evidence_type;

-- Expected evidence types:
-- JIRA_ISSUE
-- REPOSITORY_RESOLUTION
-- JENKINS_BUILD or JENKINS_BUILD_CONTEXT
-- LOKI_LOGS (optional)
-- ROOT_CAUSE_ANALYSIS (optional)


-- ========================================
-- Test 7: Case Details
-- ========================================
SELECT 
    'Case Details Test' AS test_name,
    id,
    jira_key,
    target_key,
    status,
    synthetic,
    jenkins_job_name,
    jenkins_build_number,
    created_at
FROM rf_case
WHERE id = '36c12a20-cd58-4588-9c62-adaf62dea38e';

-- Expected:
-- jira_key     | target_key | synthetic | status
-- FIZZMS-8346  | backend    | false     | ...


-- ========================================
-- Test 8: Repository Resolution Required Fields
-- ========================================
SELECT 
    'Required Fields Test' AS test_name,
    id,
    CASE 
        WHEN content_text::json->>'projectKey' IS NULL OR content_text::json->>'projectKey' = '' 
        THEN 'FAILED: projectKey missing'
        WHEN content_text::json->>'primaryRepositorySlug' IS NULL OR content_text::json->>'primaryRepositorySlug' = '' 
        THEN 'FAILED: primaryRepositorySlug missing'
        WHEN content_text::json->>'sourceBranch' IS NULL OR content_text::json->>'sourceBranch' = '' 
        THEN 'FAILED: sourceBranch missing'
        ELSE 'PASSED'
    END AS required_fields_check
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected: required_fields_check = 'PASSED'


-- ========================================
-- Test 9: Exact Match Verification
-- ========================================
SELECT 
    'Exact Match Verification' AS test_name,
    id,
    content_text::json->>'targetKey' AS target_key,
    content_text::json->>'projectKey' AS project_key,
    content_text::json->>'repositorySlug' AS repo_slug,
    content_text::json->>'resolutionMethod' AS method,
    content_text::json->>'confidence' AS confidence
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected:
-- target_key | project_key | repo_slug | method                    | confidence
-- backend    | DCE         | backend   | TARGET_CONFIG_EXACT_MATCH | 1


-- ========================================
-- Test 10: Sanitized Clone URL
-- ========================================
SELECT 
    'Clone URL Sanitization Test' AS test_name,
    id,
    content_text::json->>'cloneUrl' AS clone_url,
    content_text::json->>'sanitizedCloneUrl' AS sanitized_clone_url,
    CASE 
        WHEN content_text::json->>'cloneUrl' ~ '://[^@]+:[^@]+@' THEN 'FAILED: credentials in cloneUrl'
        WHEN content_text::json->>'sanitizedCloneUrl' ~ '://[^@]+:[^@]+@' THEN 'FAILED: credentials in sanitizedCloneUrl'
        ELSE 'PASSED'
    END AS sanitization_check
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected: sanitization_check = 'PASSED'


-- ========================================
-- Test 11: Evidence Retrieval for Different Cases
-- ========================================
SELECT 
    'Multi-Case Test' AS test_name,
    c.id AS case_id,
    c.jira_key,
    c.target_key,
    COUNT(DISTINCT e.evidence_type) AS evidence_type_count,
    COUNT(e.id) AS total_evidence
FROM rf_case c
LEFT JOIN rf_evidence e ON e.case_id = c.id
WHERE c.jira_key = 'FIZZMS-8346'
GROUP BY c.id, c.jira_key, c.target_key
ORDER BY c.created_at DESC;

-- Should show multiple cases for same jira_key but different target_keys


-- ========================================
-- Test 12: Legacy Evidence Compatibility
-- ========================================
-- This would test if old evidence can still be parsed
-- Create mock legacy evidence manually if needed

-- ========================================
-- Test 13: Repository State Validation
-- ========================================
SELECT 
    'Repository State Test' AS test_name,
    id,
    content_text::json->>'repositoryState' AS state,
    CASE 
        WHEN content_text::json->>'repositoryState' IN ('AVAILABLE', 'ARCHIVED', 'DELETED') 
        THEN 'VALID'
        ELSE 'INVALID'
    END AS state_validation
FROM rf_evidence
WHERE case_id = '36c12a20-cd58-4588-9c62-adaf62dea38e'
  AND evidence_type = 'REPOSITORY_RESOLUTION';

-- Expected: state = 'AVAILABLE', state_validation = 'VALID'


-- ========================================
-- Summary Report Query
-- ========================================
SELECT 
    '=== SUMMARY REPORT ===' AS section,
    c.id AS case_id,
    c.jira_key,
    c.target_key,
    c.status AS case_status,
    c.synthetic,
    (SELECT COUNT(*) FROM rf_evidence WHERE case_id = c.id) AS total_evidence,
    (SELECT COUNT(*) FROM rf_evidence WHERE case_id = c.id AND evidence_type = 'REPOSITORY_RESOLUTION') AS repo_resolution_count,
    (SELECT COUNT(*) FROM rf_evidence WHERE case_id = c.id AND evidence_type IN ('JENKINS_BUILD', 'JENKINS_BUILD_CONTEXT')) AS jenkins_evidence_count,
    (SELECT content_text::json->>'projectKey' FROM rf_evidence WHERE case_id = c.id AND evidence_type = 'REPOSITORY_RESOLUTION' LIMIT 1) AS project_key,
    (SELECT content_text::json->>'primaryRepositorySlug' FROM rf_evidence WHERE case_id = c.id AND evidence_type = 'REPOSITORY_RESOLUTION' LIMIT 1) AS primary_slug,
    (SELECT content_text::json->>'sourceBranch' FROM rf_evidence WHERE case_id = c.id AND evidence_type = 'REPOSITORY_RESOLUTION' LIMIT 1) AS source_branch
FROM rf_case c
WHERE c.id = '36c12a20-cd58-4588-9c62-adaf62dea38e';

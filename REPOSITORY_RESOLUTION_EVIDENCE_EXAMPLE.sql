-- Repository Resolution Evidence Example
-- After running Golden Path with targetKey=backend

-- 1. Check repository resolution evidence
SELECT 
    e.id,
    e.evidence_type,
    e.source,
    e.confidence,
    e.created_at,
    e.content_text::json->>'applicationKey' AS application_key,
    e.content_text::json->>'targetKey' AS target_key,
    e.content_text::json->>'bitbucketProjectKey' AS bitbucket_project,
    e.content_text::json->>'repositorySlug' AS repository_slug,
    e.content_text::json->>'sourceBranch' AS source_branch,
    e.content_text::json->>'resolutionMethod' AS resolution_method
FROM rf_evidence e
WHERE e.case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
  AND e.evidence_type = 'REPOSITORY_RESOLUTION'
ORDER BY e.created_at DESC;

-- 2. Full evidence content (sanitized, no secrets)
SELECT 
    e.id,
    e.evidence_type,
    e.confidence,
    e.content_text
FROM rf_evidence e
WHERE e.case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
  AND e.evidence_type = 'REPOSITORY_RESOLUTION';

-- 3. Verify no credential leak in evidence
SELECT 
    e.id,
    e.content_text
FROM rf_evidence e
WHERE e.case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
  AND e.evidence_type = 'REPOSITORY_RESOLUTION'
  AND (
    e.content_text LIKE '%password%' OR
    e.content_text LIKE '%token%' OR
    e.content_text LIKE '%secret%' OR
    e.content_text LIKE '%credential%' OR
    e.content_text LIKE '%@%:%'  -- user:pass pattern
  );
-- Should return NO rows

-- 4. All evidence for the case
SELECT 
    evidence_type,
    source,
    confidence,
    created_at,
    LEFT(content_text, 100) AS content_preview
FROM rf_evidence
WHERE case_id = '4a706e42-a3fd-4477-b5a9-2328bfad2096'
ORDER BY created_at;

-- Expected REPOSITORY_RESOLUTION evidence structure:
/*
{
  "applicationKey": "backend",
  "targetKey": "backend",
  "bitbucketProjectKey": "BSS",
  "repositorySlug": "bss-backend",
  "repositoryName": "BSS Backend",
  "cloneUrl": "https://bitbucket.etiya.com/scm/bss/bss-backend.git",
  "sourceBranch": "test2",
  "confidence": 1.0,
  "matchedSignals": ["EXACT_TARGET_CONFIG_MATCH"],
  "resolutionMethod": "TARGET_CONFIG_EXACT_MATCH",
  "repositoryState": "AVAILABLE"
}
*/

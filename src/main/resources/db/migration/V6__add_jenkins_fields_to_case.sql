-- V5: Add Jenkins fields to rf_case table
-- These fields store Jenkins job and build information for evidence correlation

ALTER TABLE rf_case ADD COLUMN IF NOT EXISTS jenkins_job_name VARCHAR(300);
ALTER TABLE rf_case ADD COLUMN IF NOT EXISTS jenkins_build_number INTEGER;

COMMENT ON COLUMN rf_case.jenkins_job_name IS 'Jenkins job name that produced the build';
COMMENT ON COLUMN rf_case.jenkins_build_number IS 'Jenkins build number';

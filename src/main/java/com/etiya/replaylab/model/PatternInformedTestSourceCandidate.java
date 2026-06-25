package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record PatternInformedTestSourceCandidate(
        UUID caseId,
        String repositorySlug,
        String sourceCommitSha,
        String patternPath,
        String targetProductionClass,
        String targetProductionMethod,
        String proposedPackage,
        String proposedClassName,
        String proposedMethodName,
        String proposedRelativePath,
        String framework,
        String testStyle,
        String source,
        String contentSha256,
        TestSourceReadiness readiness,
        double compileConfidence,
        List<String> reusedImports,
        List<String> reusedAnnotations,
        List<String> detectedDependencies,
        List<String> unresolvedSymbols,
        List<String> assumptions,
        List<String> warnings,
        boolean fileWritten,
        boolean testExecuted,
        boolean humanApprovalRequired
) {
}

package com.etiya.replaylab.model;

/**
 * Branch strategy configuration for ReplayLab.
 * Target branch: test2
 * Fix branch format: bugfix/{jiraKey}-{short-description}
 * Example: bugfix/FIZZMS-8346-replaylab
 */
public record BranchStrategy(
        String targetBranch,
        String fixBranchPrefix,
        String fixBranchName,
        boolean fetchBeforeCreate,
        boolean useExactTargetHead
) {
    public static BranchStrategy forJiraKey(String jiraKey, String shortDescription) {
        String fixBranch = String.format("bugfix/%s-%s", jiraKey, shortDescription);
        return new BranchStrategy(
                "test2",
                "bugfix/",
                fixBranch,
                true,
                true
        );
    }
    
    public static BranchStrategy defaultStrategy(String jiraKey) {
        return forJiraKey(jiraKey, "replaylab");
    }
}

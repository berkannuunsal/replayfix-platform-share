package com.etiya.replaylab.model;

import java.util.List;

public record TestPatternCandidate(
        String relativePath,
        String packageName,
        String className,
        String framework,
        String testStyle,
        int score,
        List<String> reasons,
        List<String> imports,
        List<String> annotations,
        List<String> testMethods,
        List<String> mockedTypes,
        String excerpt
) {
}

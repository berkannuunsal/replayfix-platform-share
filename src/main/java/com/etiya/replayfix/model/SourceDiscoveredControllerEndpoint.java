package com.etiya.replayfix.model;

public record SourceDiscoveredControllerEndpoint(
        String file,
        String className,
        String methodName,
        String httpMethod,
        String classPath,
        String methodPath,
        String fullPath
) {
    public SourceDiscoveredControllerEndpoint {
        file = file == null ? "" : file;
        className = className == null ? "" : className;
        methodName = methodName == null ? "" : methodName;
        httpMethod = httpMethod == null ? "" : httpMethod;
        classPath = classPath == null ? "" : classPath;
        methodPath = methodPath == null ? "" : methodPath;
        fullPath = fullPath == null ? "" : fullPath;
    }
}

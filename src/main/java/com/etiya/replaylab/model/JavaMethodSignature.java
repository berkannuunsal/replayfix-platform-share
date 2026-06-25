package com.etiya.replaylab.model;

import java.util.List;

public record JavaMethodSignature(
        String methodName,
        String returnType,
        List<JavaParameterSignature> parameters
) {
}

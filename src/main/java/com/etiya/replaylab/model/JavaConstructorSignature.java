package com.etiya.replaylab.model;

import java.util.List;

public record JavaConstructorSignature(
        String className,
        List<JavaParameterSignature> parameters
) {
}

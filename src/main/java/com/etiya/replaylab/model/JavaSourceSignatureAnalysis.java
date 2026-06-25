package com.etiya.replaylab.model;

import java.util.List;

public record JavaSourceSignatureAnalysis(
        String packageName,
        String className,
        JavaConstructorSignature constructor,
        JavaMethodSignature targetMethod,
        List<String> fieldTypes,
        List<String> imports,
        List<String> warnings
) {
}

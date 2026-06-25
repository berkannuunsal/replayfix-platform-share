package com.etiya.replaylab.service;

import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceSignatureAnalyzerTest {

    private JavaSourceSignatureAnalyzer analyzer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        analyzer = new JavaSourceSignatureAnalyzer(objectMapper);
    }

    @Test
    void shouldExtractPackageName() throws Exception {
        String sourceContext = createSourceContext(
                "package com.example.service;\n\npublic class MyService {}"
        );

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "MyService",
                "doSomething",
                sourceContext
        );

        assertEquals("com.example.service", result.packageName());
    }

    @Test
    void shouldExtractClassName() throws Exception {
        String sourceContext = createSourceContext(
                "package com.example;\n\npublic class NotificationService {}"
        );

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "NotificationService",
                "sendEmail",
                sourceContext
        );

        assertEquals("NotificationService", result.className());
    }

    @Test
    void shouldExtractConstructorParameters() throws Exception {
        String sourceContext = createSourceContext(
                "package com.example;\n\n" +
                "public class MyService {\n" +
                "    public MyService(EmailService emailService, UserRepository userRepository) {}\n" +
                "}"
        );

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "MyService",
                "process",
                sourceContext
        );

        assertNotNull(result.constructor());
        assertEquals(2, result.constructor().parameters().size());
        assertEquals("EmailService", result.constructor().parameters().get(0).type());
        assertEquals("emailService", result.constructor().parameters().get(0).name());
        assertEquals("UserRepository", result.constructor().parameters().get(1).type());
    }

    @Test
    void shouldExtractMethodSignature() throws Exception {
        String sourceContext = createSourceContext(
                "package com.example;\n\n" +
                "public class MyService {\n" +
                "    public String doSomething(Long userId, String message) {\n" +
                "        return null;\n" +
                "    }\n" +
                "}"
        );

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "MyService",
                "doSomething",
                sourceContext
        );

        assertNotNull(result.targetMethod());
        assertEquals("doSomething", result.targetMethod().methodName());
        assertEquals("String", result.targetMethod().returnType());
        assertEquals(2, result.targetMethod().parameters().size());
        assertEquals("Long", result.targetMethod().parameters().get(0).type());
        assertEquals("userId", result.targetMethod().parameters().get(0).name());
    }

    @Test
    void shouldWarnOnOverloadedMethod() throws Exception {
        String sourceContext = createSourceContext(
                "package com.example;\n\n" +
                "public class MyService {\n" +
                "    public void doSomething(String value) {}\n" +
                "    public void doSomething(String value, int count) {}\n" +
                "}"
        );

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "MyService",
                "doSomething",
                sourceContext
        );

        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("overloaded")));
    }

    @Test
    void shouldPreferTargetClassExcerpt() throws Exception {
        String sourceContext = """
                {
                    "excerpts": [
                        "package com.other; public class OtherClass {}",
                        "package com.example; public class TargetClass { public TargetClass(String dep) {} }"
                    ]
                }
                """;

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "TargetClass",
                "method",
                sourceContext
        );

        assertEquals("com.example", result.packageName());
        assertEquals("TargetClass", result.constructor().className());
    }

    @Test
    void shouldHandleMissingSourceGracefully() throws Exception {
        String sourceContext = createSourceContext("");

        JavaSourceSignatureAnalysis result = analyzer.analyze(
                "UnknownClass",
                "unknownMethod",
                sourceContext
        );

        assertFalse(result.warnings().isEmpty());
        assertEquals("", result.packageName());
        assertEquals("", result.className());
    }

    private String createSourceContext(String content) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("excerpts", java.util.List.of(content))
        );
    }
}

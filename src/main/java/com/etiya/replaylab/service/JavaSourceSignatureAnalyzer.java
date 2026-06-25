package com.etiya.replaylab.service;

import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JavaSourceSignatureAnalyzer {

    private final ObjectMapper objectMapper;

    public JavaSourceSignatureAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JavaSourceSignatureAnalysis analyze(
            String targetClass,
            String targetMethod,
            String sourceContextJson
    ) {
        List<String> warnings = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(sourceContextJson);
            
            String targetExcerpt = findTargetClassExcerpt(
                    root,
                    targetClass,
                    warnings
            );

            if (targetExcerpt == null || targetExcerpt.isBlank()) {
                warnings.add("Target class excerpt not found in source context.");
                return createEmptyAnalysis(warnings);
            }

            String packageName = extractPackage(targetExcerpt);
            String className = targetClass;

            JavaConstructorSignature constructor = extractConstructor(
                    targetExcerpt,
                    className,
                    warnings
            );

            JavaMethodSignature method = extractMethod(
                    targetExcerpt,
                    targetMethod,
                    warnings
            );

            List<String> fieldTypes = extractFieldTypes(targetExcerpt);
            List<String> imports = extractImports(targetExcerpt);

            return new JavaSourceSignatureAnalysis(
                    packageName,
                    className,
                    constructor,
                    method,
                    fieldTypes,
                    imports,
                    warnings
            );

        } catch (Exception exception) {
            warnings.add("Cannot parse source context JSON: " + exception.getMessage());
            return createEmptyAnalysis(warnings);
        }
    }

    private String findTargetClassExcerpt(
            JsonNode root,
            String targetClass,
            List<String> warnings
    ) {
        if (root.has("excerpts") && root.get("excerpts").isArray()) {
            List<String> candidates = new ArrayList<>();
            
            for (JsonNode excerptNode : root.get("excerpts")) {
                String content = excerptNode.asText();
                candidates.add(content);
                
                if (content.contains("class " + targetClass)
                        || content.contains("interface " + targetClass)) {
                    return content;
                }
            }

            if (!candidates.isEmpty()) {
                warnings.add("Target class not found in excerpts; using first available.");
                return candidates.get(0);
            }
        }

        if (root.has("content")) {
            return root.get("content").asText();
        }

        return "";
    }

    private String extractPackage(String source) {
        Pattern pattern = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private JavaConstructorSignature extractConstructor(
            String source,
            String className,
            List<String> warnings
    ) {
        Pattern pattern = Pattern.compile(
                "public\\s+" + Pattern.quote(className) + "\\s*\\(([^)]*)\\)",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);
        
        List<String> foundConstructors = new ArrayList<>();
        
        while (matcher.find()) {
            foundConstructors.add(matcher.group(1));
        }

        if (foundConstructors.isEmpty()) {
            warnings.add("No public constructor found; assuming default constructor.");
            return new JavaConstructorSignature(className, List.of());
        }

        if (foundConstructors.size() > 1) {
            warnings.add("Multiple constructors found; using first match.");
        }

        String params = foundConstructors.get(0).trim();
        
        if (params.isEmpty()) {
            return new JavaConstructorSignature(className, List.of());
        }

        List<JavaParameterSignature> parameters = parseParameters(params);

        return new JavaConstructorSignature(className, parameters);
    }

    private JavaMethodSignature extractMethod(
            String source,
            String targetMethod,
            List<String> warnings
    ) {
        Pattern pattern = Pattern.compile(
                "(\\w+(?:<[^>]+>)?)\\s+" + Pattern.quote(targetMethod) + "\\s*\\(([^)]*)\\)",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);
        
        List<MethodMatch> matches = new ArrayList<>();
        
        while (matcher.find()) {
            String returnType = matcher.group(1);
            String params = matcher.group(2);
            matches.add(new MethodMatch(returnType, params));
        }

        if (matches.isEmpty()) {
            warnings.add("Target method signature not found in source.");
            return new JavaMethodSignature(targetMethod, "void", List.of());
        }

        if (matches.size() > 1) {
            warnings.add("Method is overloaded; using first signature.");
        }

        MethodMatch selected = matches.get(0);
        List<JavaParameterSignature> parameters = parseParameters(selected.params.trim());

        return new JavaMethodSignature(
                targetMethod,
                selected.returnType,
                parameters
        );
    }

    private List<JavaParameterSignature> parseParameters(String params) {
        if (params.isBlank()) {
            return List.of();
        }

        List<JavaParameterSignature> result = new ArrayList<>();
        String[] parts = params.split(",");

        for (String part : parts) {
            part = part.trim();
            
            if (part.isEmpty()) {
                continue;
            }

            String[] tokens = part.split("\\s+");
            
            if (tokens.length < 2) {
                continue;
            }

            String type = tokens[tokens.length - 2];
            String name = tokens[tokens.length - 1];

            result.add(new JavaParameterSignature(type, name));
        }

        return result;
    }

    private List<String> extractFieldTypes(String source) {
        Set<String> fieldTypes = new LinkedHashSet<>();
        
        Pattern pattern = Pattern.compile(
                "private\\s+(?:final\\s+)?([A-Za-z0-9_<>]+)\\s+[a-z][a-zA-Z0-9_]*\\s*;",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);

        while (matcher.find()) {
            fieldTypes.add(matcher.group(1));
        }

        return new ArrayList<>(fieldTypes);
    }

    private List<String> extractImports(String source) {
        List<String> imports = new ArrayList<>();
        
        Pattern pattern = Pattern.compile(
                "import\\s+(?:static\\s+)?([a-zA-Z0-9_.$]+)\\s*;",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);

        while (matcher.find()) {
            imports.add(matcher.group(1));
        }

        return imports;
    }

    private JavaSourceSignatureAnalysis createEmptyAnalysis(List<String> warnings) {
        return new JavaSourceSignatureAnalysis(
                "",
                "",
                new JavaConstructorSignature("", List.of()),
                new JavaMethodSignature("", "void", List.of()),
                List.of(),
                List.of(),
                warnings
        );
    }

    private record MethodMatch(String returnType, String params) {}
}

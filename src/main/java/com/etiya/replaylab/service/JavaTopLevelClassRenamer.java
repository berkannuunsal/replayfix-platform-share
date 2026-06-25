package com.etiya.replaylab.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JavaTopLevelClassRenamer {

    public RenameResult rename(
            String source,
            String originalClassName,
            String targetClassName
    ) {
        validateIdentifier(originalClassName, "Original class name");
        validateIdentifier(targetClassName, "Target class name");

        if (originalClassName.equals(targetClassName)) {
            return new RenameResult(source, 0, 0);
        }

        ClassRenameResult classMatches = renameClassDeclaration(
                source,
                originalClassName,
                targetClassName
        );

        if (classMatches.classDeclarationMatches() == 0) {
            throw new IllegalStateException(
                    "No top-level class declaration found for: "
                            + originalClassName
            );
        }

        if (classMatches.classDeclarationMatches() > 1) {
            throw new IllegalStateException(
                    "Multiple class declarations found; expected exactly one."
            );
        }

        String renamedSource = classMatches.source();

        ConstructorRenameResult constructorMatches = renameConstructorDeclaration(
                renamedSource,
                originalClassName,
                targetClassName
        );

        if (constructorMatches.constructorDeclarationMatches() > 0) {
            renamedSource = constructorMatches.source();
        }

        return new RenameResult(
                renamedSource,
                classMatches.classDeclarationMatches(),
                constructorMatches.constructorDeclarationMatches()
        );
    }

    private ClassRenameResult renameClassDeclaration(
            String source,
            String originalClassName,
            String targetClassName
    ) {
        Pattern pattern = Pattern.compile(
                "(?m)^(\\s*(?:public\\s+)?(?:final\\s+)?class\\s+)"
                        + Pattern.quote(originalClassName)
                        + "(\\b)",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);
        StringBuffer result = new StringBuffer();
        int count = 0;

        while (matcher.find()) {
            matcher.appendReplacement(
                    result,
                    matcher.group(1) + targetClassName + matcher.group(2)
            );
            count++;
        }

        matcher.appendTail(result);

        return new ClassRenameResult(result.toString(), count);
    }

    private ConstructorRenameResult renameConstructorDeclaration(
            String source,
            String originalClassName,
            String targetClassName
    ) {
        Pattern pattern = Pattern.compile(
                "(?m)^(\\s*(?:public\\s+|private\\s+|protected\\s+)?)"
                        + Pattern.quote(originalClassName)
                        + "(\\s*\\()",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(source);
        StringBuffer result = new StringBuffer();
        int count = 0;

        while (matcher.find()) {
            matcher.appendReplacement(
                    result,
                    matcher.group(1) + targetClassName + matcher.group(2)
            );
            count++;
        }

        matcher.appendTail(result);

        return new ConstructorRenameResult(result.toString(), count);
    }

    private void validateIdentifier(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required."
            );
        }

        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    fieldName + " is not a valid Java identifier: " + value
            );
        }
    }

    public record RenameResult(
            String source,
            int classDeclarationMatches,
            int constructorMatches
    ) {
    }

    private record ClassRenameResult(
            String source,
            int classDeclarationMatches
    ) {
    }

    private record ConstructorRenameResult(
            String source,
            int constructorDeclarationMatches
    ) {
    }
}

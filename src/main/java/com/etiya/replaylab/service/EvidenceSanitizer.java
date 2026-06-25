package com.etiya.replaylab.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class EvidenceSanitizer {
    private final Pattern jsonSecretPattern = Pattern.compile(
            "(?i)(\"(?:authorization|cookie|token|password)\"\\s*:\\s*\")([^\"]+)(\")"
    );

    private final List<Pattern> sensitivePatterns = List.of(
            Pattern.compile("(?i)(authorization|cookie|token|password)\\s*[:=]\\s*(bearer\\s+)?[^\\s,;]+"),
            Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"),
            Pattern.compile("\\b(?:\\+?90)?5\\d{9}\\b"),
            Pattern.compile("\\b\\d{11}\\b")
    );

    public String sanitize(String text) {
        if (text == null) return "";
        String result = jsonSecretPattern.matcher(text)
                .replaceAll("$1[REDACTED]$3");
        for (Pattern pattern : sensitivePatterns) {
            result = pattern.matcher(result).replaceAll("[REDACTED]");
        }
        return result;
    }
}

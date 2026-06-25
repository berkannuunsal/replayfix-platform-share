package com.etiya.replaylab.service;

import org.springframework.stereotype.Service;

@Service
public class ContainerImageParser {

    public record ParsedContainerImage(
            String registry,
            String repository,
            String tag,
            String digest
    ) {
    }

    public ParsedContainerImage parse(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("Image cannot be null or blank");
        }

        String normalized = image.trim();

        String digest = null;
        if (normalized.contains("@sha256:")) {
            int digestIndex = normalized.indexOf("@sha256:");
            digest = normalized.substring(digestIndex + 1);
            normalized = normalized.substring(0, digestIndex);
        }

        String tag = null;
        String registryAndRepo = normalized;

        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            String afterLastSlash = normalized.substring(lastSlash + 1);
            int colonIndex = afterLastSlash.indexOf(':');

            if (colonIndex > 0) {
                tag = afterLastSlash.substring(colonIndex + 1);
                registryAndRepo = normalized.substring(0, lastSlash + 1)
                        + afterLastSlash.substring(0, colonIndex);
            }
        } else {
            int colonIndex = normalized.indexOf(':');
            if (colonIndex > 0 && !normalized.substring(0, colonIndex).contains(".")) {
                tag = normalized.substring(colonIndex + 1);
                registryAndRepo = normalized.substring(0, colonIndex);
            }
        }

        String registry = null;
        String repository = registryAndRepo;

        if (registryAndRepo.contains("/")) {
            String[] parts = registryAndRepo.split("/", 2);
            if (parts[0].contains(".") || parts[0].contains(":")) {
                registry = parts[0];
                repository = parts.length > 1 ? parts[1] : "";
            }
        }

        return new ParsedContainerImage(
                registry,
                repository,
                tag,
                digest
        );
    }
}

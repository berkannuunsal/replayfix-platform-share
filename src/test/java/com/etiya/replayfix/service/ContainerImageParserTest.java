package com.etiya.replayfix.service;

import com.etiya.replayfix.service.ContainerImageParser.ParsedContainerImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerImageParserTest {

    private ContainerImageParser parser;

    @BeforeEach
    void setUp() {
        parser = new ContainerImageParser();
    }

    @Test
    void shouldParseSimpleImageWithTag() {
        ParsedContainerImage result = parser.parse("nginx:1.21");

        assertNull(result.registry());
        assertEquals("nginx", result.repository());
        assertEquals("1.21", result.tag());
        assertNull(result.digest());
    }

    @Test
    void shouldParseImageWithRegistryAndTag() {
        ParsedContainerImage result = parser.parse("docker.io/library/nginx:1.21");

        assertEquals("docker.io", result.registry());
        assertEquals("library/nginx", result.repository());
        assertEquals("1.21", result.tag());
        assertNull(result.digest());
    }

    @Test
    void shouldParseImageWithRegistryPortAndTag() {
        ParsedContainerImage result = parser.parse("registry:5000/myapp:v1.0.0");

        assertEquals("registry:5000", result.registry());
        assertEquals("myapp", result.repository());
        assertEquals("v1.0.0", result.tag());
        assertNull(result.digest());
    }

    @Test
    void shouldParseImageWithDigest() {
        ParsedContainerImage result = parser.parse(
                "nginx@sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        );

        assertNull(result.registry());
        assertEquals("nginx", result.repository());
        assertNull(result.tag());
        assertEquals(
                "sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                result.digest()
        );
    }

    @Test
    void shouldParseImageWithTagAndDigest() {
        ParsedContainerImage result = parser.parse(
                "myregistry.io/myapp:v1.0@sha256:abcd1234"
        );

        assertEquals("myregistry.io", result.registry());
        assertEquals("myapp", result.repository());
        assertEquals("v1.0", result.tag());
        assertEquals("sha256:abcd1234", result.digest());
    }

    @Test
    void shouldParseImageWithoutTag() {
        ParsedContainerImage result = parser.parse("nginx");

        assertNull(result.registry());
        assertEquals("nginx", result.repository());
        assertNull(result.tag());
        assertNull(result.digest());
    }

    @Test
    void shouldParseImageWithDeepPath() {
        ParsedContainerImage result = parser.parse(
                "gcr.io/project/team/app:build-123"
        );

        assertEquals("gcr.io", result.registry());
        assertEquals("project/team/app", result.repository());
        assertEquals("build-123", result.tag());
        assertNull(result.digest());
    }

    @Test
    void shouldThrowExceptionForNullImage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(null)
        );
    }

    @Test
    void shouldThrowExceptionForBlankImage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("   ")
        );
    }
}

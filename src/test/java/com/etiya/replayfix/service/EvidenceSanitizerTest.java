package com.etiya.replayfix.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSanitizerTest {
    private final EvidenceSanitizer sanitizer =
            new EvidenceSanitizer();

    @Test
    void masksSecretsAndPersonalData() {
        String input = "Authorization=abc " +
                "{\"Authorization\":\"Bearer raw-token\",\"password\":\"secret\"} " +
                "mail=user@example.com " +
                "phone=5551234567 " +
                "identity=12345678901";

        String result = sanitizer.sanitize(input);

        assertThat(result)
                .doesNotContain("abc")
                .doesNotContain("raw-token")
                .doesNotContain("secret")
                .doesNotContain("user@example.com")
                .doesNotContain("5551234567")
                .doesNotContain("12345678901");
    }
}

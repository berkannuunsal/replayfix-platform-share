package com.etiya.replaylab.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchRuleRegistryTest {

    private final PatchRuleRegistry registry = new PatchRuleRegistry();

    @Test
    void returnsKnownRules() {
        assertThat(registry.findById(PatchRuleRegistry.VALIDATION_GUARD))
                .isPresent();
        assertThat(registry.findById(PatchRuleRegistry.MAPPING_FIX))
                .isPresent();
        assertThat(registry.findById(PatchRuleRegistry.DB_STATE_VALIDATION))
                .isPresent();
        assertThat(registry.rules()).hasSize(12);
    }

    @Test
    void rulesCannotWriteCode() {
        assertThat(registry.rules())
                .allSatisfy(rule -> {
                    assertThat(rule.canWriteCode()).isFalse();
                    assertThat(rule.requiresHumanApproval()).isTrue();
                    assertThat(rule.ruleId()).isNotBlank();
                    assertThat(rule.allowedLayers()).isNotEmpty();
                });
    }
}

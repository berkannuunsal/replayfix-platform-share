package com.etiya.replayfix.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDbEvidenceQueryRegistryTest {

    private final ApplicationDbEvidenceQueryRegistry registry =
            new ApplicationDbEvidenceQueryRegistry(new SqlReadOnlyGuard());

    @Test
    void returnsExpectedTemplates() {
        assertThat(registry.findById(
                ApplicationDbEvidenceQueryRegistry.USER_PREFERRED_PROVINCE
        )).isPresent();
        assertThat(registry.findById(
                ApplicationDbEvidenceQueryRegistry.USER_REGION_STATE
        )).isPresent();
        assertThat(registry.findById(
                ApplicationDbEvidenceQueryRegistry.TAX_INFO_STATE
        )).isPresent();
        assertThat(registry.findById(
                ApplicationDbEvidenceQueryRegistry.TIMEZONE_STATE
        )).isPresent();
    }

    @Test
    void templatesAreReadOnlySelectOnlyMetadata() {
        SqlReadOnlyGuard guard = new SqlReadOnlyGuard();

        assertThat(registry.templates())
                .allSatisfy(template -> {
                    assertThat(template.readOnly()).isTrue();
                    assertThat(template.sqlPreview().toLowerCase())
                            .startsWith("select");
                    guard.validateSelectOnly(template.sqlPreview());
                });
    }

    @Test
    void relevantTemplatesIncludeRegionTaxTimezoneAndBillingSignals() {
        var templates = registry.relevantTemplates(
                "/user/region/update preferredProvince taxInfo TimeZone billingAccount"
        );

        assertThat(templates)
                .extracting("templateId")
                .contains(
                        ApplicationDbEvidenceQueryRegistry.USER_PREFERRED_PROVINCE,
                        ApplicationDbEvidenceQueryRegistry.USER_REGION_STATE,
                        ApplicationDbEvidenceQueryRegistry.TAX_INFO_STATE,
                        ApplicationDbEvidenceQueryRegistry.TIMEZONE_STATE,
                        ApplicationDbEvidenceQueryRegistry.BILLING_ACCOUNT_REGION
                );
    }
}

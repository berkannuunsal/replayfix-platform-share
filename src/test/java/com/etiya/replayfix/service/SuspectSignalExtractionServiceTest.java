package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuspectSignalExtractionServiceTest {

    private SuspectSignalExtractionService service;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        service = new SuspectSignalExtractionService(
                caseRepository,
                evidenceRepository,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void shouldExtractRovoSignalsAndVariants() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.REPOSITORY_RESOLUTION,
                        "repository-resolution",
                        "{\"projectKey\":\"DCE\",\"primaryRepositorySlug\":\"backend\",\"branch\":\"test2\"}"),
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "rawHumanReport": "Billing Account Creation / Update Flow has province mismatch for billing account. /Region GL i2i",
                                  "normalizedRovoJson": {
                                    "probableRootCause": "Missing validation around /businessFlow/initialize and /user/region/update.",
                                    "minimumFixDirection": [
                                      "Validate PREFERRED_PROVINCE, region, tax_info and timezone."
                                    ]
                                  }
                                }
                                """)
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse response = service.extract(caseId);

        assertEquals("FIZZMS-10228", response.jiraKey());
        assertEquals("DCE/backend", response.repository());
        assertEquals("test2", response.branch());
        assertTrue(response.warnings().isEmpty());

        assertSignal(response, "/businessFlow/initialize");
        assertSignal(response, "/user/region/update");
        assertSignal(response, "PREFERRED_PROVINCE");
        assertSignal(response, "preferredProvince");
        assertSignal(response, "PreferredProvince");
        assertSignal(response, "preferred_province");
        assertSignal(response, "preferred-province");
        assertSignal(response, "tax_info");
        assertSignal(response, "taxInfo");
        assertSignal(response, "TaxInfo");
        assertSignal(response, "tax-info");
        assertSignal(response, "timezone");
        assertSignal(response, "timeZone");
        assertSignal(response, "TimeZone");
        assertSignal(response, "billing account");
        assertSignal(response, "BillingAccount");
        assertSignal(response, "billingAccount");
        assertSignal(response, "billing-account");
        assertSignal(response, "businessFlow");
        assertSignal(response, "BusinessFlow");
        assertSignal(response, "initialize");
        assertSignal(response, "user");
        assertSignal(response, "region");
        assertSignal(response, "Region");
        assertSignal(response, "update");
        assertSignal(response, "/Region");
        assertSignal(response, "province");
        assertSignal(response, "Province");
        assertSignal(response, "i2i");
        assertSignal(response, "GL");
        assertSignal(response, "regionUpdate");
        assertSignal(response, "RegionUpdate");
        assertSignalStrength(response, "/businessFlow/initialize", SuspectSignalStrength.STRONG);
        assertSignalStrength(response, "billing account", SuspectSignalStrength.MEDIUM);
    }

    @Test
    void shouldFilterNaturalLanguageFillerPhrases() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "rawHumanReport": "selamlar ni2i billing account at i2i description. billing account ya da update edilirken region ve timezone bilgileri. uyumsuz olan billing accountlar mevcut. sale billing sorun. account at i2i description. mismatch when or updating billing region and and timezone.",
                                  "normalizedRovoJson": {
                                    "probableRootCause": "Billing account region timezone validation is missing.",
                                    "minimumFixDirection": "Validate /businessFlow/initialize, /user/region/update, PREFERRED_PROVINCE, tax_info, GL and i2i."
                                  }
                                }
                                """)
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse response = service.extract(caseId);

        assertNoSignals(response, List.of(
                "billing account ya",
                "account ya da",
                "selamlar ni2i billing",
                "update edilirken region",
                "edilirken region ve",
                "ve timezone bilgileri",
                "uyumsuz olan billing",
                "billing accountlar mevcut",
                "sale billing sorun",
                "account at i2i description",
                "mismatch when",
                "or updating billing",
                "region and",
                "and timezone",
                "billingAccountYa",
                "accountYaDa",
                "updateEdilirkenRegion",
                "edilirkenRegionVe"
        ));
        assertSignal(response, "/businessFlow/initialize");
        assertSignal(response, "/user/region/update");
        assertSignal(response, "PREFERRED_PROVINCE");
        assertSignal(response, "preferredProvince");
        assertSignal(response, "billing account");
        assertSignal(response, "BillingAccount");
        assertSignal(response, "billingAccount");
        assertSignal(response, "tax_info");
        assertSignal(response, "taxInfo");
        assertSignal(response, "GL");
        assertSignal(response, "i2i");
        assertTrue(response.filteredCount() > 0);

        SuspectSignalExtractionResponse weakResponse =
                service.extract(caseId, true);
        assertNoSignals(weakResponse, List.of(
                "billing account ya",
                "account ya da",
                "selamlar ni2i billing",
                "update edilirken region",
                "edilirken region ve",
                "ve timezone bilgileri",
                "uyumsuz olan billing",
                "billing accountlar mevcut",
                "sale billing sorun",
                "account at i2i description",
                "mismatch when",
                "or updating billing",
                "region and",
                "and timezone",
                "billingAccountYa",
                "accountYaDa",
                "updateEdilirkenRegion",
                "edilirkenRegionVe"
        ));
    }

    @Test
    void shouldExcludeWeakSignalsByDefaultAndIncludeThemWhenRequested() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "rawHumanReport": "account creation needs review.",
                                  "normalizedRovoJson": {
                                    "probableRootCause": "account creation needs review."
                                  }
                                }
                                """)
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse defaultResponse =
                service.extract(caseId);
        assertNoSignal(defaultResponse, "account creation");

        SuspectSignalExtractionResponse weakResponse =
                service.extract(caseId, true);
        assertSignalStrength(
                weakResponse,
                "account creation",
                SuspectSignalStrength.WEAK
        );
    }

    @Test
    void shouldFilterRealisticBundleAndJiraProseNoise() {
        UUID caseId = UUID.randomUUID();

        String noisyText = """
                selamlar ni2i billing account at i2i description
                billing account ya da update edilirken region ve timezone bilgileri
                uyumsuz olan billing accountlar mevcut sale billing sorun
                account at i2i description mismatch when or updating billing
                region and and timezone
                Endpoint /businessFlow/initialize calls /user/region/update and /Region.
                PREFERRED_PROVINCE tax_info billing account province timezone GL i2i.
                """;

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.AI_INPUT_BUNDLE,
                        "ai-input-bundle",
                        "{\"context\":\"" + jsonEscape(noisyText) + "\"}"),
                evidence(caseId, EvidenceType.JIRA_ISSUE,
                        "jira",
                        "{\"summary\":\"" + jsonEscape(noisyText) + "\"}")
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse response = service.extract(caseId);

        assertFalse(response.warnings().isEmpty());
        assertNoSignals(response, List.of(
                "billing account ya",
                "account ya da",
                "selamlar ni2i billing",
                "update edilirken region",
                "edilirken region ve",
                "ve timezone bilgileri",
                "uyumsuz olan billing",
                "billing accountlar mevcut",
                "sale billing sorun",
                "account at i2i description",
                "mismatch when",
                "or updating billing",
                "region and",
                "and timezone"
        ));
        assertSignal(response, "/businessFlow/initialize");
        assertSignal(response, "/user/region/update");
        assertSignal(response, "/Region");
        assertSignal(response, "PREFERRED_PROVINCE");
        assertSignal(response, "preferredProvince");
        assertSignal(response, "PreferredProvince");
        assertSignal(response, "tax_info");
        assertSignal(response, "taxInfo");
        assertSignal(response, "TaxInfo");
        assertSignal(response, "billing account");
        assertSignal(response, "BillingAccount");
        assertSignal(response, "billingAccount");
        assertSignal(response, "region");
        assertSignal(response, "Region");
        assertSignal(response, "province");
        assertSignal(response, "timezone");
        assertSignal(response, "timeZone");
        assertSignal(response, "TimeZone");
        assertSignal(response, "GL");
        assertSignal(response, "i2i");
    }

    @Test
    void shouldDeduplicateRepeatedBusinessTerms() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.ROVO_RCA,
                        "rovo-incident-commander",
                        """
                                {
                                  "rawHumanReport": "billing account billing account Billing Account",
                                  "normalizedRovoJson": {
                                    "probableRootCause": "billing account"
                                  }
                                }
                                """)
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse response = service.extract(caseId);

        long count = response.signals()
                .stream()
                .filter(signal -> "billing account".equals(signal.value()))
                .count();

        assertEquals(1, count);
    }

    @Test
    void shouldWarnWhenRovoMissingButOtherEvidenceExists() {
        UUID caseId = UUID.randomUUID();

        List<EvidenceEntity> evidence = List.of(
                evidence(caseId, EvidenceType.JIRA_ISSUE,
                        "jira",
                        "{\"summary\":\"billing account province mismatch\"}")
        );

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(caseEntity(caseId)));
        when(evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId))
                .thenReturn(evidence);

        SuspectSignalExtractionResponse response = service.extract(caseId);

        assertFalse(response.warnings().isEmpty());
        assertSignal(response, "billing account");
        assertSignal(response, "province mismatch");
    }

    private void assertSignal(
            SuspectSignalExtractionResponse response,
            String value
    ) {
        assertTrue(
                response.signals()
                        .stream()
                        .anyMatch(signal -> value.equals(signal.value())),
                "Expected signal not found: " + value
        );
    }

    private void assertNoSignal(
            SuspectSignalExtractionResponse response,
            String value
    ) {
        assertFalse(
                response.signals()
                        .stream()
                        .anyMatch(signal -> value.equals(signal.value())),
                "Unexpected signal found: " + value
        );
    }

    private void assertNoSignals(
            SuspectSignalExtractionResponse response,
            List<String> values
    ) {
        values.forEach(value -> assertNoSignal(response, value));
    }

    private void assertSignalStrength(
            SuspectSignalExtractionResponse response,
            String value,
            SuspectSignalStrength strength
    ) {
        assertEquals(
                strength,
                response.signals()
                        .stream()
                        .filter(signal -> value.equals(signal.value()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "Expected signal not found: " + value
                        ))
                        .strength()
        );
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setStatus(ReplayCaseStatus.NEW);
        replayCase.setSourceBranch("test2");
        return replayCase;
    }

    private EvidenceEntity evidence(
            UUID caseId,
            EvidenceType type,
            String source,
            String contentText
    ) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(type);
        evidence.setSource(source);
        evidence.setContentText(contentText);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }

    private String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}

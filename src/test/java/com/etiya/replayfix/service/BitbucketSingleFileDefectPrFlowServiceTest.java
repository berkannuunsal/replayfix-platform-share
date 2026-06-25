package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketSingleFileDefectPrFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketSingleFileDefectPrFlowResponse;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BitbucketSingleFileDefectPrFlowServiceTest {

    @Test
    void deprecatedSingleFileServiceDelegatesToTargetedChangeService() {
        UUID caseId = UUID.randomUUID();
        DefectPrTargetedChangeService targeted = mock(DefectPrTargetedChangeService.class);
        when(targeted.preview(eq(caseId), any()))
                .thenReturn(targetedResponse(caseId));
        BitbucketSingleFileDefectPrFlowService service =
                new BitbucketSingleFileDefectPrFlowService(targeted);

        BitbucketSingleFileDefectPrFlowResponse response =
                service.preview(caseId, new BitbucketSingleFileDefectPrFlowRequest(
                        "berkan", "DCE", "backend", "CRM-123", "Safe summary",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "", "", "GENERATED_TEST_ONLY",
                        "[DRAFT] ReplayFix", true, false, false));

        assertThat(response.filePath())
                .isEqualTo("ControllerBackend/src/test/java/com/etiya/replayfix/generated/CRM123RegressionTest.java");
        assertThat(response.changeMode()).isEqualTo("TARGETED_TEST_CHANGE");
        assertThat(response.previewOnly()).isTrue();
    }

    private DefectPrTargetedChangeResponse targetedResponse(UUID caseId) {
        return new DefectPrTargetedChangeResponse(
                caseId,
                "CRM-123",
                "Safe summary",
                false,
                true,
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/CRM-123",
                "Integration/test2/CRM-123",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/CRM123RegressionTest.java",
                "TARGETED_TEST_CHANGE",
                "CRM-123: Safe summary",
                "",
                "",
                "",
                "",
                "[DRAFT] ReplayFix CRM-123 fix proposal",
                false,
                true,
                false,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.now()
        );
    }
}

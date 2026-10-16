package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelInsightExportService;
import io.github.opensabre.iqc.label.LabelResultQueryService;
import io.github.opensabre.iqc.privacy.ResultReadProjection;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.HierarchicalResultService;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InspectionResultSensitiveControllerTest {
    @Test
    void currentResultGrantIsCheckedBeforeConversationLookup() {
        var sensitive = mock(SensitiveReadAccessService.class);
        when(sensitive.current(Set.of("iqc:result:view"))).thenThrow(IqcException.accessDenied("revoked"));
        var batch = mock(BatchResultQueryService.class);
        var controller = new InspectionResultController(mock(InspectionExecutionService.class), mock(UsageCounterRecorder.class),
                batch, mock(HierarchicalResultService.class), mock(LabelResultQueryService.class),
                mock(LabelInsightExportService.class), mock(BusinessItemReviewService.class), sensitive,
                new ResultReadProjection(new ObjectMapper()));

        assertThrows(IqcException.class, () -> controller.conversationResultDetail("conversation-1"));
        verify(sensitive).current(Set.of("iqc:result:view"));
        verifyNoInteractions(batch);
    }
}

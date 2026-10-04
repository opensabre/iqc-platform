package io.github.opensabre.iqc.rest;

import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.HierarchicalResultService;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.label.LabelResultQueryService;
import io.github.opensabre.iqc.label.LabelInsightExportService;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract tests for inspection result endpoints. */
class InspectionResultControllerTest {

    @Test
    void businessResultEndpointReturnsConversationTotalAndCanonicalZeroScore() throws Exception {
        var permission = InspectionResultController.class.getMethod("businessResults", long.class, long.class,
                String.class, String.class, String.class, java.math.BigDecimal.class, java.math.BigDecimal.class)
                .getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class);
        org.junit.jupiter.api.Assertions.assertEquals("iqc:result:business:view", permission.code());
        var batch = mock(BatchResultQueryService.class);
        when(batch.businessPage(1, 1, "task-1", "FINAL", null, java.math.BigDecimal.ZERO, null))
                .thenReturn(new io.github.opensabre.iqc.shared.IqcPage<>(java.util.List.of(
                        new BatchResultQueryService.BusinessConversationRow("canonical", "task-1", "电话销售质检", "conversation-1",
                                "sales.txt", "attempt-2", "FINAL", java.math.BigDecimal.ZERO, 2, 2, 0, "HIGH")), 1, 1, 2));
        var controller = new InspectionResultController(mock(InspectionExecutionService.class), mock(UsageCounterRecorder.class),
                batch, mock(HierarchicalResultService.class), mock(LabelResultQueryService.class),
                mock(LabelInsightExportService.class), mock(io.github.opensabre.iqc.quality.BusinessItemReviewService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RestResponseBodyAdvice()).build();

        mvc.perform(get("/api/iqc/results/business").param("taskId", "task-1").param("size", "1")
                        .param("scoreStatus", "FINAL").param("minScore", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].finalScore").value(0))
                .andExpect(jsonPath("$.data.records[0].taskName").value("电话销售质检"))
                .andExpect(jsonPath("$.data.records[0].id").value("canonical"));
        verify(batch).businessPage(1, 1, "task-1", "FINAL", null, java.math.BigDecimal.ZERO, null);
    }

    @Test
    void returnsJsonNullWhenHierarchyHasNotBeenGenerated() throws Exception {
        InspectionResultController controller = new InspectionResultController(
                mock(InspectionExecutionService.class), mock(UsageCounterRecorder.class),
                mock(BatchResultQueryService.class), mock(HierarchicalResultService.class),
                mock(LabelResultQueryService.class), mock(LabelInsightExportService.class),
                mock(io.github.opensabre.iqc.quality.BusinessItemReviewService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestResponseBodyAdvice())
                .build();

        mvc.perform(get("/api/iqc/tasks/task-1/conversations/conversation-1/result-hierarchy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}

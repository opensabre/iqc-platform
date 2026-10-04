package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionResultExportTest {
    private final InspectionExecutionService execution = mock(InspectionExecutionService.class);
    private final BatchResultQueryService batch = mock(BatchResultQueryService.class);
    private final io.github.opensabre.iqc.quality.BusinessItemReviewService reviews = mock(io.github.opensabre.iqc.quality.BusinessItemReviewService.class);
    private final InspectionResultController controller = new InspectionResultController(execution, null, batch, null, null, null, reviews);

    @Test
    void sensitiveExportRoutesKeepExportPermissionAndAudit() throws Exception {
        var methods = java.util.List.of(
                InspectionResultController.class.getMethod("exportLabelResults", String.class),
                InspectionResultController.class.getMethod("export", String.class, String.class, String.class, String.class,
                        String.class, Integer.class, Integer.class, String.class, String.class, String.class, String.class));

        for (var method : methods) {
            var permission = method.getAnnotation(ResourcePermission.class);
            var audit = method.getAnnotation(Audit.class);
            assertThat(permission).isNotNull();
            assertThat(permission.code()).isEqualTo("iqc:result:export");
            assertThat(audit).isNotNull();
            assertThat(audit.operationType()).isEqualTo(OperationType.EXPORT);
            assertThat(audit.module()).isEqualTo("IQC_RESULT");
        }
    }

    @Test
    void businessViewUsesExistingEndpointWithoutLegacyExport() {
        when(batch.exportSchemeCsv("t1")).thenReturn("业务结果");
        var response = controller.export("t1", null, null, null, null, null, null, null, null, "SCHEME", null);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("iqc-business-results.csv");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("业务结果");
        verifyNoInteractions(execution);
    }

    @Test
    void rejectsUnknownViewAndMessageFiltersInsteadOfSilentlyIgnoringThem() {
        assertThatThrownBy(() -> controller.export("t1", null, null, null, "HIT", null, null, null, null, "SCHEME", null))
                .hasMessageContaining("不支持消息筛选");
        assertThatThrownBy(() -> controller.export("t1", null, null, null, null, null, null, null, null, "UNKNOWN", null))
                .hasMessageContaining("不支持的结果导出视图");
        verifyNoInteractions(execution, batch);
    }

    @Test
    void exportsOnlyTheExplicitReviewAndRejectsMixedSelectors() {
        when(reviews.exportCsv("round-1")).thenReturn("复核结果");
        var response = controller.export(null, null, null, null, null, null, null, null, null, "BUSINESS_REVIEW", "round-1");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("iqc-business-review.csv");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("复核结果");
        assertThatThrownBy(() -> controller.export("task", null, null, null, null, null, null, null, null, "BUSINESS_REVIEW", "round-1"))
                .hasMessageContaining("只能指定复核轮次");
        assertThatThrownBy(() -> controller.export(null, null, null, null, null, null, null, null, null, "MESSAGE", "round-1"))
                .hasMessageContaining("不得指定复核轮次");
        verifyNoInteractions(execution, batch);
    }

    @Test
    void legacyMessageExportKeepsItsFiltersAndFilename() {
        when(execution.exportCsv("legacy", null, null, null, "HIT", null, null, null, null)).thenReturn("legacy csv");
        var response = controller.export("legacy", null, null, null, "HIT", null, null, null, null, "MESSAGE", null);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("filename=iqc-results.csv");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("legacy csv");
        verifyNoInteractions(batch, reviews);
    }

    @Test
    void batchReviewExportReturnsZipAndRejectsMixedFilters() {
        byte[] zip = {80, 75, 3, 4};
        when(reviews.exportTaskZip("task")).thenReturn(zip);
        var response = controller.export("task", null, null, null, null, null, null, null, null, "BUSINESS_REVIEW_TASK", null);
        assertThat(response.getBody()).isEqualTo(zip);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("iqc-task-reviews.zip");
        assertThatThrownBy(() -> controller.export("task", null, null, null, "HIT", null, null, null, null, "BUSINESS_REVIEW_TASK", null))
                .hasMessageContaining("只能指定任务");
        assertThatThrownBy(() -> controller.export("task", null, null, null, null, null, null, null, null, "BUSINESS_REVIEW_TASK", "round"))
                .hasMessageContaining("只能指定任务");
        verifyNoInteractions(execution, batch);
    }

    @Test
    void httpNegotiatesZipWithoutWrappingBinaryInJson() throws Exception {
        byte[] zip = {80, 75, 3, 4};
        when(reviews.exportTaskZip("task")).thenReturn(zip);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/results/export")
                .param("view", "BUSINESS_REVIEW_TASK").param("taskId", "task").accept("application/zip"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(zip));
    }
}

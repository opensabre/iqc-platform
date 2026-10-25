package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.quality.*;
import io.github.opensabre.iqc.label.LabelResultReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP binding and serialization only; service authorization is independently tested. */
class ActionMetricsReportHttpTest {
    private final BusinessQualityReportService reports=mock(BusinessQualityReportService.class);
    private final QualityOperationsService legacy=mock(QualityOperationsService.class);
    private QualityOperationsController controller;
    private MockMvc http;
    @BeforeEach void setup(){
        controller=new QualityOperationsController(legacy,mock(BusinessItemReviewService.class),reports,mock(LabelResultReviewService.class));
        http=MockMvcBuilders.standaloneSetup(controller).build();
    }
    private BusinessQualityReportService.Report report(boolean metrics){
        return new BusinessQualityReportService.Report("SELECTED_TASK_RUNS","SELECTED_RUNS",1,1,List.of(),metrics?
                new BusinessQualityReportService.ActionMetrics("iqc-business-action-metrics-v1",1,1,0,"9007199254740993","1","2","9007199254740990",new BigDecimal("66.67"),List.of()):null);
    }
    @Test void defaultBusinessRequestUsesLegacyOverloadAndOmitsMetrics() throws Exception {
        when(reports.report(List.of("task"),null,"SELECTED_RUNS")).thenReturn(report(false));
        http.perform(get("/api/iqc/quality-operations/report").param("view","BUSINESS").param("taskIds","task"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionMetrics").doesNotExist());
        verify(reports).report(List.of("task"),null,"SELECTED_RUNS");verifyNoMoreInteractions(reports);verifyNoInteractions(legacy);
    }
    @Test void explicitActionMetricsBindAllFiltersAndPreserveLargeStringsWithNoStore() throws Exception {
        String selections="{\"task\":\"execution\"}";
        when(reports.report(List.of("task"),selections,"SELECTED_RUNS",false,true)).thenReturn(report(true));
        http.perform(get("/api/iqc/quality-operations/report").param("view","BUSINESS").param("taskIds","task")
                .param("executionSelections",selections).param("includeActionMetrics","true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.actionMetrics.actionCount").value("9007199254740993"))
                .andExpect(jsonPath("$.actionMetrics.confirmedFailureRate").value(66.67));
        verify(reports).report(List.of("task"),selections,"SELECTED_RUNS",false,true);
    }
    @Test void combinedOptionsReachCombinedServiceAndInvalidBooleanCannotRead() throws Exception {
        when(reports.report(List.of("task"),null,"SELECTED_RUNS",true,true)).thenReturn(report(true));
        http.perform(get("/api/iqc/quality-operations/report").param("view","BUSINESS").param("taskIds","task")
                .param("includeModelUsage","true").param("includeActionMetrics","true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(reports).report(List.of("task"),null,"SELECTED_RUNS",true,true);clearInvocations(reports);
        http.perform(get("/api/iqc/quality-operations/report").param("view","BUSINESS").param("includeActionMetrics","garbage"))
                .andExpect(status().isBadRequest());verifyNoInteractions(reports,legacy);
    }
    @Test void oldDirectMethodSignaturesRemainAvailable(){
        controller.report("BUSINESS",List.of("task"),null,"SELECTED_RUNS");
        controller.report("BUSINESS",List.of("task"),null,"SELECTED_RUNS",true);
        verify(reports).report(List.of("task"),null,"SELECTED_RUNS");
        verify(reports).report(List.of("task"),null,"SELECTED_RUNS",true);
    }
}

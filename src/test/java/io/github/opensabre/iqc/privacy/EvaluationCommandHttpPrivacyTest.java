package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.evaluation.PipelineEvaluationService;
import io.github.opensabre.iqc.rest.PipelineEvaluationController;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Command receipts and report transport use real MVC/projection; authorization and persistence are isolated. */
class EvaluationCommandHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final PipelineEvaluationService service=mock(PipelineEvaluationService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private MockMvc mvc(PipelineEvaluationController controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RestResponseBodyAdvice(),
                new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    }
    private MockMvc mvc(){return mvc(new PipelineEvaluationController(service,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt) {
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));
    }
    private InspectionTask task() {
        var task=new InspectionTask();task.setId("t");task.setName("Call 13812345678");task.setStatus("PENDING");
        task.setCurrentExecutionId("e");task.setProcessedMessages(0);task.setFailedMessages(0);task.setOwnerGroupId("group");
        task.setAgentSnapshotJson("secret-agent");task.setRuleSnapshotJson("secret-rule");task.setLabelScopeSnapshotJson("secret-label");
        task.setAutoExpandPrompt("secret-prompt");task.setTriggerRequestJson("secret-trigger");task.setRetryPolicyJson("secret-retry-policy");
        task.setSelectionFilterJson("secret-filter");task.setConversationIdsJson("secret-conversations");task.setRuleIdsJson("secret-rule-ids");
        task.setRetryError("secret-retry-error");task.setTriggerError("secret-trigger-error");task.setTriggerRequestHash("request-hash");return task;
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void commandGrantReturnsReceiptWithoutFrozenInputsEvenWithAllSensitiveGrants(boolean full,boolean pii,boolean prompt) throws Exception {
        allow(full,pii,prompt);var source=task();String original=json.writeValueAsString(source);
        when(service.create("d",1,"p",2,null,"name",3,"request")).thenReturn(source);
        String response=mvc().perform(post("/api/iqc/evaluation/tasks").contentType("application/json").content("""
                {"datasetId":"d","datasetVersion":1,"pipelineId":"p","pipelineVersion":2,"name":"name","concurrency":3,"requestId":"request"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.data.id").value("t")).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.processedMessages").value(0))
                .andExpect(jsonPath("$.data.currentExecutionId").value("e"))
                .andExpect(jsonPath("$.data.triggerRequestHash").value("request-hash"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("secret-"));assertEquals(full&&pii,response.contains("13812345678"));
        assertEquals(original,json.writeValueAsString(source));assertEquals("secret-retry-policy",source.getRetryPolicyJson());
        verify(grants).current(Set.of("iqc:evaluation:task:create"));
        verify(service).create("d",1,"p",2,null,"name",3,"request");
    }
    @Test void reportPreservesMissingSamplesSourcesAndExactMetrics() throws Exception {
        allow(false,false,false);
        var metrics=new PipelineEvaluationService.BusinessMetrics(2,1,new BigDecimal("0.12345678901234567890123456789"),
                new BigDecimal("0.5"),1,1,0,BigDecimal.ZERO,1,0,BigDecimal.ZERO);
        var report=new PipelineEvaluationService.Report("schema","t","e","hash",2,1,1,false,
                List.of("missing"),Map.of("sample","previous-execution"),null,metrics,null,null,null);
        when(service.report("t","e")).thenReturn(report);
        String response=mvc().perform(get("/api/iqc/evaluation/tasks/t/report?executionId=e"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.data.expectedSamples").value(2))
                .andExpect(jsonPath("$.data.availableSamples").value(1)).andExpect(jsonPath("$.data.complete").value(false))
                .andExpect(jsonPath("$.data.missingSampleIds[0]").value("missing"))
                .andExpect(jsonPath("$.data.sourceExecutionIds.sample").value("previous-execution"))
                .andExpect(jsonPath("$.data.businessMetrics.highRiskRecall").value(0))
                .andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("0.12345678901234567890123456789"));
        verify(grants).current(Set.of("iqc:evaluation:report:view"));verify(service).report("t","e");
    }
    @Test void consistencyTransportBindsBothExplicitExecutionsAndPreservesUnmeasuredValues() throws Exception {
        allow(false,false,false);
        var report=new io.github.opensabre.iqc.quality.evaluation.ModelConsistencyMetrics.Report(
                "iqc-model-consistency-v1","scope",2,0,0,BigDecimal.ZERO,null,Map.of("SAME_ARTIFACT",1,"MISSING_ARTIFACT",1));
        when(service.consistency("t","selected","reference-task","reference-execution")).thenReturn(report);
        mvc().perform(get("/api/iqc/evaluation/tasks/t/report")
                .param("executionId","selected").param("referenceTaskId","reference-task")
                .param("referenceExecutionId","reference-execution"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.data.expectedPairs").value(2))
                .andExpect(jsonPath("$.data.comparablePairs").value(0))
                .andExpect(jsonPath("$.data.comparisonCoverage").value(0))
                .andExpect(jsonPath("$.data.agreementRate").doesNotExist())
                .andExpect(jsonPath("$.data.excludedReasons.SAME_ARTIFACT").value(1));
        verify(grants).current(Set.of("iqc:evaluation:report:view"));
        verify(service).consistency("t","selected","reference-task","reference-execution");
    }
    @Test void consistencyAuthorizationFailurePreventsAnyArtifactQuery() throws Exception {
        when(grants.current(anySet())).thenThrow(new IllegalStateException("unavailable"));
        mvc().perform(get("/api/iqc/evaluation/tasks/t/report")
                .param("executionId","selected").param("referenceTaskId","reference-task")
                .param("referenceExecutionId","reference-execution"))
                .andExpect(jsonPath("$.code").value("IQC-1006"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(grants).current(Set.of("iqc:evaluation:report:view"));verifyNoInteractions(service);
    }
    @Test void deniedCurrentAccessPreventsCreationAndReportQuery() throws Exception {
        when(grants.current(anySet())).thenThrow(new IllegalStateException("unavailable"));var mvc=mvc();
        mvc.perform(post("/api/iqc/evaluation/tasks").contentType("application/json").content("{\"datasetVersion\":1}"))
                .andExpect(jsonPath("$.code").value("IQC-1006")).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/iqc/evaluation/tasks/t/report"))
                .andExpect(jsonPath("$.code").value("IQC-1006")).andExpect(jsonPath("$.data").doesNotExist());
        verify(grants).current(Set.of("iqc:evaluation:task:create"));verify(grants).current(Set.of("iqc:evaluation:report:view"));
        verifyNoInteractions(service);
    }
    @Test void legacyConstructorFailsClosedForProtectedOperations() throws Exception {
        var mvc=mvc(new PipelineEvaluationController(service));
        mvc.perform(post("/api/iqc/evaluation/tasks").contentType("application/json").content("{\"datasetVersion\":1}"))
                .andExpect(jsonPath("$.code").value("IQC-1006"));
        mvc.perform(get("/api/iqc/evaluation/tasks/t/report")).andExpect(jsonPath("$.code").value("IQC-1006"));
        verifyNoInteractions(service);
    }
}

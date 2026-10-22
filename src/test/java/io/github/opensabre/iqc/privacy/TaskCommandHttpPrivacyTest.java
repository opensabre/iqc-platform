package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.selector.*;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC and response projections; task persistence, worker submission and authorization are isolated. */
class TaskCommandHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final InspectionTaskService tasks=mock(InspectionTaskService.class);
    private final InspectionExecutionService executions=mock(InspectionExecutionService.class);
    private final PipelineAssetService assets=mock(PipelineAssetService.class);
    private final SelectorAssetService selectors=mock(SelectorAssetService.class);
    private final UsageCounterRecorder usage=mock(UsageCounterRecorder.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private final PipelineAssetService.Prepared release=mock(PipelineAssetService.Prepared.class);
    private PipelineAssetController pipeline() {
        var controller=new PipelineAssetController(assets,tasks,grants,projection);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"selectorAssets",selectors);return controller;
    }
    private InspectionResultController result(){return new InspectionResultController(executions,usage,null,null,null,null,null,grants,projection);}
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private void allow(boolean full,boolean pii){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,true,Instant.now()));}
    private InspectionTask task(){var row=new InspectionTask();row.setId("t");row.setName("Call 13812345678");row.setStatus("QUEUED");row.setCurrentExecutionId("e");row.setProcessedMessages(0);
        row.setAgentSnapshotJson("private-agent");row.setRuleSnapshotJson("private-rule");row.setLabelScopeSnapshotJson("private-label");row.setAutoExpandPrompt("private-prompt");
        row.setTriggerRequestJson("private-trigger");row.setRetryError("private-error");row.setSelectionFilterJson("private-filter");return row;}
    private String receipt(MockMvc mvc,MockHttpServletRequestBuilder request,boolean full,boolean pii) throws Exception {
        String body=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.data.id").value("t")).andExpect(jsonPath("$.data.currentExecutionId").value("e"))
                .andExpect(jsonPath("$.data.processedMessages").value(0)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-"));assertEquals(full&&pii,body.contains("13812345678"));return body;
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void taskCommandsAndRunResumeReturnCopiesAndKeepExecutionSubmission(boolean full,boolean pii) throws Exception {
        allow(full,pii);var row=task();String original=json.writeValueAsString(row);
        when(tasks.createConfigured("name","BATCH",List.of("c"),null,null,null,null,null,null,List.of("r"),1,null,null,"RULE_ONLY")).thenReturn(row);
        when(tasks.cancel("t")).thenReturn(row);when(tasks.requestPause("t")).thenReturn(row);when(tasks.changePriority("t",0L)).thenReturn(row);
        when(executions.queue("t")).thenReturn(row);when(executions.resume("t")).thenReturn(row);
        var mvc=mvc(new InspectionTaskController(tasks,usage,grants,projection),result());
        receipt(mvc,post("/api/iqc/tasks").contentType("application/json").content("{\"name\":\"name\",\"taskType\":\"BATCH\",\"conversationId\":\"c\",\"ruleIds\":[\"r\"],\"concurrencyLimit\":1,\"executionMode\":\"RULE_ONLY\"}"),full,pii);
        for(String action:List.of("cancel","pause","run","resume"))receipt(mvc,post("/api/iqc/tasks/t/"+action),full,pii);
        receipt(mvc,put("/api/iqc/tasks/t/priority").contentType("application/json").content("{}"),full,pii);
        // Existing void-response wrapping is owned by the framework; the command has no task body.
        String deleted=mvc.perform(delete("/api/iqc/tasks/t")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(deleted.contains("private-"));
        verify(executions,times(2)).executeAsync("t","e");verify(usage,times(2)).record(any());verify(tasks).deleteTerminal("t");
        verify(grants).current(Set.of("iqc:task:create"));verify(grants).current(Set.of("iqc:task:cancel"));verify(grants,times(4)).current(Set.of("iqc:task:execute"));verify(grants).current(Set.of("iqc:task:delete"));
        assertEquals(original,json.writeValueAsString(row));
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void pipelineTrialAndAllPublishedSelectionBranchesReturnMetadata(boolean full,boolean pii) throws Exception {
        allow(full,pii);var row=task();String original=json.writeValueAsString(row);
        when(assets.frozenDraft("p",3)).thenReturn(release);when(assets.publishedRelease("p",2)).thenReturn(release);
        when(tasks.createUsingPipelineSnapshot(eq(release),eq("name"),eq(List.of("c")),eq(1),eq("req"))).thenReturn(row);
        when(tasks.createUsingPipelineSelector(eq(release),eq("name"),any(),eq(1),eq("req"))).thenReturn(row);
        var selected=mock(SelectorAssetService.Prepared.class);when(selectors.published("s",4)).thenReturn(selected);
        when(tasks.createUsingPipelineSelectorAsset(release,"name",selected,1,"req")).thenReturn(row);
        var mvc=mvc(pipeline());
        receipt(mvc,post("/api/iqc/pipelines/p/trial").contentType("application/json").content("{\"expectedRevision\":3,\"name\":\"name\",\"conversationIds\":[\"c\"],\"requestId\":\"req\"}"),full,pii);
        String prefix="{\"versionNo\":2,\"name\":\"name\",\"concurrency\":1,\"requestId\":\"req\",";
        receipt(mvc,post("/api/iqc/pipelines/p/tasks").contentType("application/json").content(prefix+"\"conversationIds\":[\"c\"]}"),full,pii);
        receipt(mvc,post("/api/iqc/pipelines/p/tasks").contentType("application/json").content(prefix+"\"selector\":{\"schemaVersion\":\"iqc-selector-v1\",\"maximumCount\":1,\"condition\":{\"field\":\"messageCount\",\"operator\":\"GT\",\"value\":0.12345678901234567890123456789}}}"),full,pii);
        receipt(mvc,post("/api/iqc/pipelines/p/tasks").contentType("application/json").content(prefix+"\"selectorAsset\":{\"selectorId\":\"s\",\"versionNo\":4}}"),full,pii);
        var captured=org.mockito.ArgumentCaptor.forClass(SelectorDefinition.class);verify(tasks).createUsingPipelineSelector(eq(release),eq("name"),captured.capture(),eq(1),eq("req"));
        assertEquals(new java.math.BigDecimal("0.12345678901234567890123456789"),captured.getValue().condition().value().decimalValue());
        verify(grants).current(Set.of("iqc:pipeline:execute"));verify(grants,times(3)).current(Set.of("iqc:pipeline:task:create"));assertEquals(original,json.writeValueAsString(row));
    }
    @Test void grantRevocationPreventsEveryWriteFreezeAndWorkerCall() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        var mvc=mvc(new InspectionTaskController(tasks,usage,grants,projection),result(),pipeline());
        var requests=new ArrayList<MockHttpServletRequestBuilder>();requests.add(post("/api/iqc/tasks").contentType("application/json").content("{}"));
        for(String action:List.of("cancel","pause","run","resume"))requests.add(post("/api/iqc/tasks/t/"+action));
        requests.add(put("/api/iqc/tasks/t/priority").contentType("application/json").content("{}"));requests.add(delete("/api/iqc/tasks/t"));
        requests.add(post("/api/iqc/pipelines/p/trial").contentType("application/json").content("{\"expectedRevision\":1}"));requests.add(post("/api/iqc/pipelines/p/tasks").contentType("application/json").content("{\"versionNo\":1}"));
        for(var request:requests)mvc.perform(request).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(tasks,executions,usage,assets,selectors);
    }
    @Test void conflictingSelectorInputsStillRejectBeforeTaskCreation() throws Exception {
        allow(false,false);when(assets.publishedRelease("p",2)).thenReturn(release);
        mvc(pipeline()).perform(post("/api/iqc/pipelines/p/tasks").contentType("application/json")
                .content("{\"versionNo\":2,\"conversationIds\":[\"c\"],\"selectorAsset\":{\"selectorId\":\"s\",\"versionNo\":4}}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(tasks,selectors);
    }
}

package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.InspectionTaskController;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Frozen configuration HTTP contract; actual organization and task persistence are isolated. */
class TaskConfigurationHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final InspectionTaskService tasks=mock(InspectionTaskService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final Set<String> required=Set.of("iqc:task:view",SensitiveReadAccessService.PROMPT);
    private MockMvc mvc(InspectionTaskController controller){return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                    new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new InspectionTaskController(tasks,mock(UsageCounterRecorder.class),grants,new ResultReadProjection(json)));}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void configurationRequiresEverySensitiveGrantBeforeTaskRead(boolean full,boolean pii,boolean prompt)throws Exception{
        when(grants.current(required)).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));
        var source=new InspectionTask();source.setId("task");source.setName("Call 13812345678");
        source.setAgentSnapshotJson("private-agent");source.setRuleSnapshotJson("{\"private\":\"rule\"}");
        source.setLabelScopeSnapshotJson("private-label");source.setAutoExpandPrompt("private-prompt");
        source.setSelectionFilterJson("private-filter");source.setTriggerRequestJson("private-trigger");
        source.setTriggerRequestHash("original-hash");String original=json.writeValueAsString(source);
        when(tasks.get("task")).thenReturn(source);
        var result=mvc().perform(get("/api/iqc/tasks/task/configuration"));
        if(full&&pii&&prompt){
            var response=result.andExpect(jsonPath("$.code").value("000000"))
                    .andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString();
            var data=json.readTree(response).path("data");
            assertEquals(source.getAgentSnapshotJson(),data.path("agentSnapshotJson").asText());
            assertEquals(source.getRuleSnapshotJson(),data.path("ruleSnapshotJson").asText());
            assertEquals(source.getLabelScopeSnapshotJson(),data.path("labelScopeSnapshotJson").asText());
            assertEquals(source.getAutoExpandPrompt(),data.path("autoExpandPrompt").asText());
            assertEquals("original-hash",data.path("triggerRequestHash").asText());
            assertTrue(data.at("/privacy/snapshotsVisible").asBoolean());
            assertFalse(response.contains("private-filter"));assertFalse(response.contains("private-trigger"));
            verify(tasks).get("task");
        }else{
            result.andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
            verifyNoInteractions(tasks);
        }
        verify(grants).current(required);assertEquals(original,json.writeValueAsString(source));
    }
    @Test void revokedBaseResourcesAndUnavailableWiringCannotReadTask()throws Exception{
        when(grants.current(required)).thenThrow(IqcException.accessDenied("revoked"));
        mvc().perform(get("/api/iqc/tasks/task/configuration")).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        mvc(new InspectionTaskController(tasks,mock(UsageCounterRecorder.class))).perform(get("/api/iqc/tasks/task/configuration"))
                .andExpect(jsonPath("$.code").value("IQC-1006")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(tasks);
    }
    @Test void sensitiveGrantsDoNotBypassOwningTaskScope()throws Exception{
        when(grants.current(required)).thenReturn(new SensitiveReadAccessService.Access("test",true,true,true,Instant.now()));
        when(tasks.get("foreign-task")).thenThrow(IqcException.accessDenied("outside task scope"));
        mvc().perform(get("/api/iqc/tasks/foreign-task/configuration")).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        var order=inOrder(grants,tasks);order.verify(grants).current(required);order.verify(tasks).get("foreign-task");
    }
    @Test void independentResourceRetainsBoundedRateLimitAndBodyFreeAudit()throws Exception{
        var method=InspectionTaskController.class.getMethod("configuration",String.class);
        assertEquals(SensitiveReadAccessService.PROMPT,method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class).code());
        var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);
        assertFalse(audit.request());assertFalse(audit.response());assertEquals("#id",audit.key());
        var limit=method.getAnnotation(io.github.opensabre.governance.ratelimit.annotations.RateLimit.class);
        assertEquals("iqc-task-configuration-query",limit.sceneCode());assertEquals(30,limit.maxCount());assertEquals(60,limit.period());
    }
}

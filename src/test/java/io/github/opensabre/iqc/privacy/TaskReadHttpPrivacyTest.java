package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
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

/** Default MVC read boundaries with real projections; organization and persistence are isolated. */
class TaskReadHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final InspectionTaskService tasks=mock(InspectionTaskService.class);
    private final PeriodicScheduleService periodic=mock(PeriodicScheduleService.class);
    private final EventSubscriptionService events=mock(EventSubscriptionService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new InspectionTaskController(tasks,mock(UsageCounterRecorder.class),grants,projection),
            new PeriodicScheduleController(periodic,grants,projection),new EventSubscriptionController(events,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    private InspectionTask row(){var row=new InspectionTask();row.setId("b");row.setName("Call 13812345678");row.setTaskType("PIPELINE_EVALUATION");
        row.setStatus("WAITING_INPUT");row.setProcessedMessages(0);row.setFailedMessages(0);row.setCurrentExecutionId("e");row.setTriggerEventId("event");
        row.setTriggerRequestHash("hash");row.setPlannedAtUtc(java.time.LocalDateTime.parse("2030-01-01T00:00:00"));
        row.setConversationIdsJson("[\"c1\",\"c2\"]");row.setAgentSnapshotJson("private-agent");row.setLabelScopeSnapshotJson("private-label");row.setAutoExpandPrompt("private-prompt");
        row.setRuleSnapshotJson("{\"rules\":[],\"private\":\"private-rule\",\"benchmarkSnapshot\":{\"schemaVersion\":\"iqc-benchmark-pair-v1\",\"side\":\"BASELINE\",\"peerTaskId\":\"c\"}}");
        row.setSelectionFilterJson("private-filter");row.setTriggerRequestJson("private-trigger");row.setRetryError("private-retry-error");row.setTriggerError("private-trigger-error");return row;}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void detailNeedsThreeGrantsButAllListsAndInstancesAlwaysWithholdInputs(boolean full,boolean pii,boolean prompt) throws Exception {
        allow(full,pii,prompt);var source=row();String original=json.writeValueAsString(source);var page=new IqcPage<>(List.of(source),2,3,9);
        when(tasks.get("b")).thenReturn(source);when(tasks.page(2,3,null,null,null,null)).thenReturn(page);
        when(periodic.instances("p",2,3)).thenReturn(page);when(events.instances("s",2,3)).thenReturn(page);
        var run=new InspectionTaskService.TaskRunSummary("e",2,"FAILED",0,null,new Date(1700000000000L),true);when(tasks.executions("b")).thenReturn(List.of(run));
        var mvc=mvc();String detail=mvc.perform(get("/api/iqc/tasks/b")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        var data=json.readTree(detail).path("data");boolean snapshots=full&&pii&&prompt;
        assertEquals(snapshots,data.at("/privacy/snapshotsVisible").asBoolean());assertEquals(snapshots,detail.contains("private-agent"));assertEquals(snapshots,detail.contains("private-prompt"));
        if(snapshots)assertEquals(source.getRuleSnapshotJson(),data.path("ruleSnapshotJson").asText());
        for(String privateValue:List.of("private-filter","private-trigger","private-retry-error","private-trigger-error"))assertFalse(detail.contains(privateValue));
        assertEquals(full&&pii,detail.contains("13812345678"));assertEquals(2,data.at("/taskPresentation/conversationCount").asInt());assertEquals("b",data.at("/taskPresentation/benchmarkBaselineTaskId").asText());
        for(String path:List.of("/api/iqc/tasks?current=2&size=3","/api/iqc/periodic-schedules/p/instances?current=2&size=3","/api/iqc/event-subscriptions/s/instances?current=2&size=3")){
            String body=mvc.perform(get(path)).andExpect(jsonPath("$.code").value("000000")).andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.size").value(3)).andExpect(jsonPath("$.data.total").value(9))
                    .andExpect(jsonPath("$.data.records[0].triggerEventId").value("event")).andExpect(jsonPath("$.data.records[0].triggerRequestHash").value("hash"))
                    .andExpect(jsonPath("$.data.records[0].status").value("WAITING_INPUT")).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-"));assertEquals(full&&pii,body.contains("13812345678"));assertEquals(2,json.readTree(body).at("/data/records/0/taskPresentation/conversationCount").asInt());
        }
        String attempts=mvc.perform(get("/api/iqc/tasks/b/executions")).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        // Use the default HTTP mapper generation, not the domain Jackson 2 date format.
        var expected=json.readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(List.of(run)));
        var actual=json.readTree(attempts).path("data");
        assertEquals(expected,actual);
        assertEquals(run.createdTime().toInstant(),Instant.parse(actual.get(0).path("createdTime").asText()));
        assertEquals(original,json.writeValueAsString(source));
        verify(grants).current(Set.of("iqc:task:view"));verify(grants).current(Set.of("iqc:task:detail:view"));verify(grants).current(Set.of("iqc:task:executions:view"));verify(grants).current(Set.of("iqc:schedule:instances:view"));verify(grants).current(Set.of("iqc:subscription:instances:view"));
    }
    @Test void countsRemainUnknownForMissingOrDamagedSourcesAndZeroOnlyForKnownEmpty(){
        allow(false,false,false);var access=grants.current(Set.of());var source=row();
        for(String invalid:Arrays.asList(null,"{bad","{}","[1]")){source.setConversationIdsJson(invalid);assertNull(projection.taskRead(source,access,false).getTaskPresentation().get("conversationCount"));}
        source.setConversationIdsJson("[]");assertEquals(0,projection.taskRead(source,access,false).getTaskPresentation().get("conversationCount"));
        source.setConversationIdsJson(null);source.setConversationId("one");assertEquals(1,projection.taskRead(source,access,false).getTaskPresentation().get("conversationCount"));
    }
    @Test void benchmarkNavigationNeedsValidSideDistinctIdentitiesAndSchema() {
        allow(false,false,false);var access=grants.current(Set.of());var source=row();
        source.setId("c");source.setRuleSnapshotJson("{\"benchmarkSnapshot\":{\"schemaVersion\":\"iqc-benchmark-pair-v1\",\"side\":\"CANDIDATE\",\"peerTaskId\":\"b\"}}");
        assertEquals("b",projection.taskRead(source,access,false).getTaskPresentation().get("benchmarkBaselineTaskId"));
        for(String marker:List.of("{bad","{\"benchmarkSnapshot\":{\"schemaVersion\":\"iqc-benchmark-pair-v1\",\"side\":\"CANDIDATE\",\"peerTaskId\":\"c\"}}",
                "{\"benchmarkSnapshot\":{\"schemaVersion\":\"unknown\",\"side\":\"CANDIDATE\",\"peerTaskId\":\"b\"}}",
                "{\"benchmarkSnapshot\":{\"schemaVersion\":\"iqc-benchmark-pair-v1\",\"side\":\"UNKNOWN\",\"peerTaskId\":\"b\"}}")){
            source.setRuleSnapshotJson(marker);assertFalse(projection.taskRead(source,access,false).getTaskPresentation().containsKey("benchmarkBaselineTaskId"));
        }
    }
    @Test void currentGrantFailureStopsAllReadDomains() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));var mvc=mvc();
        for(String path:List.of("/api/iqc/tasks","/api/iqc/tasks/b","/api/iqc/tasks/b/executions","/api/iqc/periodic-schedules/p/instances","/api/iqc/event-subscriptions/s/instances"))
            mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(tasks,periodic,events);
    }
    @Test void missingSensitiveWiringFailsClosedForAllReadPaths() throws Exception {
        var mvc=mvc(new InspectionTaskController(tasks,mock(UsageCounterRecorder.class)),new PeriodicScheduleController(periodic),new EventSubscriptionController(events));
        for(String path:List.of("/api/iqc/tasks","/api/iqc/tasks/b","/api/iqc/tasks/b/executions","/api/iqc/periodic-schedules/p/instances","/api/iqc/event-subscriptions/s/instances"))
            mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1006"));
        verifyNoInteractions(tasks,periodic,events);
    }
    @Test void scheduleAndSubscriptionWritesNeverAuditBodies(){
        int count=0;
        for(Class<?> type:List.of(PeriodicScheduleController.class,EventSubscriptionController.class))for(var method:type.getDeclaredMethods()){
            var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);
            if(audit!=null&&audit.operationType()!=io.github.opensabre.governance.audit.annotations.OperationType.QUERY){assertFalse(audit.request(),method.getName());assertFalse(audit.response(),method.getName());count++;}
        }
        assertEquals(8,count);
    }
}

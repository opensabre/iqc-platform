package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.scheme.*;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import java.time.Instant;
import java.time.LocalDateTime;
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

/** Public scheme facts survive safe receipts; frozen inputs and external authorization stay isolated. */
class SchemeTaskPresentationHttpTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final InspectionSchemeService schemes=mock(InspectionSchemeService.class);
    private final SchemePublicationService publication=mock(SchemePublicationService.class);
    private final SchemeTaskService tasks=mock(SchemeTaskService.class);
    private final InspectionExecutionService execution=mock(InspectionExecutionService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private InspectionSchemeController controller(){return new InspectionSchemeController(schemes,publication,tasks,grants,projection);}
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private void allow(boolean full,boolean pii){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,true,Instant.now()));}
    private InspectionTask row(){var row=new InspectionTask();row.setId("t");row.setName("Call 13812345678");row.setCurrentExecutionId("e");
        row.setRuleSnapshotJson("{\"schemeSnapshot\":{\"draftRevision\":3,\"selectedVariantCode\":\"standard\",\"release\":{\"definition\":{\"items\":[],\"labels\":[{\"private\":\"private-label\"}]}}},\"prompt\":\"private-prompt\"}");
        row.setAgentSnapshotJson("private-agent");row.setAutoExpandPrompt("private-expansion");return row;}
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void allSchemeBranchesAndRunResumeKeepPublicFactsWithoutFrozenBodies(boolean full,boolean pii) throws Exception {
        allow(full,pii);var row=row();String original=json.writeValueAsString(row);
        when(publication.trial("s",3,List.of("c"),"request-1234567890","standard",2,new java.math.BigDecimal("0.8"))).thenReturn(row);
        when(publication.trials("s")).thenReturn(List.of(row));
        when(tasks.create("s",1,"name",List.of("c"),1,"request-1234567890")).thenReturn(row);
        when(tasks.create("s",1,"name",List.of("c"),1,"request-1234567890","standard")).thenReturn(row);
        var scheduled=LocalDateTime.parse("2030-01-01T10:00:00");
        when(tasks.createScheduled("s",1,"name",null,scheduled,1,"request-1234567890")).thenReturn(row);
        when(tasks.createScheduled("s",1,"name",null,scheduled,1,"request-1234567890","standard")).thenReturn(row);
        when(execution.queue("t")).thenReturn(row);when(execution.resume("t")).thenReturn(row);
        var mvc=mvc(controller(),new InspectionResultController(execution,mock(UsageCounterRecorder.class),null,null,null,null,null,grants,projection));
        var requests=new ArrayList<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>();
        requests.add(post("/api/iqc/schemes/s/trials").contentType("application/json").content("{\"expectedRevision\":3,\"conversationIds\":[\"c\"],\"requestId\":\"request-1234567890\",\"variantCode\":\"standard\",\"runCount\":2,\"confidenceThreshold\":0.8}"));
        requests.add(get("/api/iqc/schemes/s/trials"));
        for(String type:List.of("BATCH","SCHEDULED"))for(boolean variant:List.of(false,true)){
            String body="{\"name\":\"name\",\"requestId\":\"request-1234567890\",\"concurrency\":1,\"taskType\":\""+type+"\","+
                    (type.equals("BATCH")?"\"conversationIds\":[\"c\"]":"\"scheduledTime\":\"2030-01-01T10:00:00\"")+(variant?",\"variantCode\":\"standard\"":"")+"}";
            requests.add(post("/api/iqc/schemes/s/versions/1/tasks").contentType("application/json").content(body));
        }
        requests.add(post("/api/iqc/tasks/t/run"));requests.add(post("/api/iqc/tasks/t/resume"));
        for(int i=0;i<requests.size();i++){
            String body=mvc.perform(requests.get(i)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                    .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-"));assertEquals(full&&pii,body.contains("13812345678"));var data=json.readTree(body).path("data");if(i==1)data=data.get(0);
            var meta=data.path("taskPresentation");assertEquals("iqc-task-presentation-v1",meta.path("schemaVersion").asText());assertEquals("SCHEME",meta.path("kind").asText());
            assertTrue(meta.path("labelOnly").asBoolean());assertTrue(meta.path("hasLabels").asBoolean());assertEquals(3,meta.path("schemeDraftRevision").asInt());assertEquals("standard",meta.path("selectedVariantCode").asText());
        }
        verify(tasks).create("s",1,"name",List.of("c"),1,"request-1234567890");verify(tasks).create("s",1,"name",List.of("c"),1,"request-1234567890","standard");
        verify(tasks).createScheduled("s",1,"name",null,scheduled,1,"request-1234567890");verify(tasks).createScheduled("s",1,"name",null,scheduled,1,"request-1234567890","standard");
        verify(execution,times(2)).executeAsync("t","e");verify(grants).current(Set.of("iqc:scheme:trial:create"));verify(grants).current(Set.of("iqc:scheme:trial:view"));verify(grants,times(4)).current(Set.of("iqc:scheme:use"));
        assertEquals(original,json.writeValueAsString(row));assertNull(row.getTaskPresentation());
    }
    @Test void malformedOrAmbiguousSnapshotsStayUnknownAndInvalidRevisionCannotBecomePublicationEvidence(){
        allow(false,false);var access=grants.current(Set.of());var row=row();
        for(String raw:List.of("{bad","{}","{\"schemeSnapshot\":{},\"pipelineSnapshot\":{}}")){
            row.setRuleSnapshotJson(raw);var shown=projection.taskReceipt(row,access).getTaskPresentation();assertEquals("UNKNOWN",shown.get("kind"));assertNull(shown.get("labelOnly"));assertFalse(shown.containsKey("schemeDraftRevision"));
        }
        row.setRuleSnapshotJson("{\"schemeSnapshot\":{\"draftRevision\":1.5,\"selectedVariantCode\":\"private value\",\"release\":{\"definition\":{\"items\":[{}],\"labels\":[]}}}}");
        var shown=projection.taskReceipt(row,access).getTaskPresentation();assertEquals(false,shown.get("labelOnly"));assertEquals(false,shown.get("hasLabels"));assertFalse(shown.containsKey("schemeDraftRevision"));assertFalse(shown.containsKey("selectedVariantCode"));
    }
    @Test void currentGrantRevocationPreventsSchemeReadsAndWrites() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));var mvc=mvc(controller());
        var requests=List.of(get("/api/iqc/schemes/s/trials"),post("/api/iqc/schemes/s/trials").contentType("application/json").content("{\"expectedRevision\":3,\"conversationIds\":[\"c\"]}"),
                post("/api/iqc/schemes/s/versions/1/tasks").contentType("application/json").content("{\"requestId\":\"request-1234567890\",\"conversationIds\":[\"c\"]}"));
        for(var request:requests)mvc.perform(request).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(tasks,schemes,publication);
    }
}

package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real default MVC and projection; scheduler domain and current organization grants are isolated. */
class ScheduleConfigurationHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final PeriodicScheduleService periodic=mock(PeriodicScheduleService.class);
    private final EventSubscriptionService subscription=mock(EventSubscriptionService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private final PeriodicSchedule p=new PeriodicSchedule();
    private final EventSubscription e=new EventSubscription();
    private final PeriodicScheduleRevision pr=new PeriodicScheduleRevision();
    private final EventSubscriptionRevision er=new EventSubscriptionRevision();
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(
            new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
            new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new PeriodicScheduleController(periodic,grants,projection),new EventSubscriptionController(subscription,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    private void fixture(){
        p.setId("id");p.setCode("code");p.setName("contact 13812345678");p.setDraftConfigJson("private-draft");p.setDraftRevision(17);p.setActiveRevisionId("active");p.setStatus("ACTIVE");p.setOwnerGroupId("group");p.setLastError("private-error");p.setNextPlannedAtUtc(LocalDateTime.of(2030,1,2,3,4,5));p.setRetryNotBeforeUtc(LocalDateTime.of(2030,1,2,4,5,6));
        e.setId("id");e.setCode("code");e.setName(p.getName());e.setDraftConfigJson(p.getDraftConfigJson());e.setDraftRevision(17);e.setActiveRevisionId("active");e.setStatus("ACTIVE");e.setOwnerGroupId("group");e.setLastError(p.getLastError());e.setLastScannedAtUtc(p.getNextPlannedAtUtc());e.setRetryNotBeforeUtc(p.getRetryNotBeforeUtc());
        pr.setId("revision");pr.setScheduleId("id");pr.setVersionNo(23);pr.setSourceDraftRevision(17);pr.setSnapshotJson("private-snapshot");pr.setContentHash("a".repeat(64));
        er.setId("revision");er.setSubscriptionId("id");er.setVersionNo(23);er.setSourceDraftRevision(17);er.setSnapshotJson(pr.getSnapshotJson());er.setContentHash(pr.getContentHash());
        when(periodic.page(2,3)).thenReturn(new IqcPage<>(List.of(p),2,3,9));when(subscription.page(2,3)).thenReturn(new IqcPage<>(List.of(e),2,3,9));
        when(periodic.get("id")).thenReturn(p);when(subscription.get("id")).thenReturn(e);when(periodic.history("id")).thenReturn(List.of(pr));when(subscription.history("id")).thenReturn(List.of(er));
        when(periodic.create("code","name",null)).thenReturn(p);when(subscription.create("code","name",null)).thenReturn(e);
        when(periodic.update("id",17,"name",null)).thenReturn(p);when(subscription.update("id",17,"name",null)).thenReturn(e);
        when(periodic.publish("id",17)).thenReturn(pr);when(subscription.publish("id",17)).thenReturn(er);
        when(periodic.availability("id",true)).thenReturn(p);when(subscription.availability("id",true)).thenReturn(e);
    }
    private ObjectNode tree(Object source)throws Exception{return (ObjectNode)json.readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(source));}
    private com.fasterxml.jackson.databind.JsonNode response(MockMvc mvc,MockHttpServletRequestBuilder request)throws Exception{
        return json.readTree(mvc.perform(request).andExpect(jsonPath("$.code").value("000000")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private void sameMetadata(Object source,com.fasterxml.jackson.databind.JsonNode actual,String... hidden)throws Exception{
        var expected=tree(source);var copy=(ObjectNode)actual.deepCopy();expected.remove("privacy");copy.remove("privacy");
        for(String field:hidden){expected.remove(field);copy.remove(field);}assertEquals(expected,copy);
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void detailAndHistoryRequireAllThreeWhileDirectoryAlwaysWithholds(boolean full,boolean pii,boolean prompt)throws Exception{
        fixture();allow(full,pii,prompt);var mvc=mvc();boolean reveal=full&&pii&&prompt;
        String before=json.writeValueAsString(List.of(p,e,pr,er));
        for(String type:List.of("periodic-schedules","event-subscriptions")){
            String base="/api/iqc/"+type;String resource=type.equals("periodic-schedules")?"schedule":"subscription";Object source=resource.equals("schedule")?p:e;Object revision=resource.equals("schedule")?pr:er;
            var detail=response(mvc,get(base+"/id"));assertEquals(reveal,detail.path("privacy").path("canonicalPayloadVisible").asBoolean());assertEquals(reveal,detail.toString().contains("private-draft"));assertEquals(reveal,detail.toString().contains("private-error"));sameMetadata(source,detail,"name","draftConfigJson","lastError");
            assertEquals(full&&pii,detail.toString().contains("13812345678"));
            var page=response(mvc,get(base+"?current=2&size=3"));assertEquals(2,page.path("current").asInt());assertEquals(3,page.path("size").asInt());assertEquals(9,page.path("total").asInt());assertFalse(page.toString().contains("private-draft"));assertFalse(page.toString().contains("private-error"));sameMetadata(source,page.path("records").get(0),"name","draftConfigJson","lastError");
            var history=response(mvc,get(base+"/id/versions")).get(0);assertEquals(reveal,history.toString().contains("private-snapshot"));sameMetadata(revision,history,"snapshotJson");
            verify(grants).current(Set.of("iqc:"+resource+":detail:view"));verify(grants).current(Set.of("iqc:"+resource+":view"));verify(grants).current(Set.of("iqc:"+resource+":versions:view"));
        }
        assertEquals(before,json.writeValueAsString(List.of(p,e,pr,er)));verifyNoMoreInteractions(grants);
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void everyWriteReceiptWithholdsBodyEvenWithFullGrants(boolean full,boolean pii,boolean prompt)throws Exception{
        fixture();allow(full,pii,prompt);var mvc=mvc();String before=json.writeValueAsString(List.of(p,e,pr,er));
        for(String type:List.of("periodic-schedules","event-subscriptions")){
            String base="/api/iqc/"+type;String resource=type.equals("periodic-schedules")?"schedule":"subscription";
            for(var request:List.of(post(base).content("{\"code\":\"code\",\"name\":\"name\"}"),put(base+"/id").content("{\"expectedRevision\":17,\"name\":\"name\"}"),post(base+"/id/publish").content("{\"expectedRevision\":17}"),post(base+"/id/availability").content("{\"enabled\":true}"))){
                var data=response(mvc,request.contentType(MediaType.APPLICATION_JSON));assertFalse(data.toString().contains("private-"));assertFalse(data.path("privacy").path("canonicalPayloadVisible").asBoolean());
                sameMetadata(data.has("versionNo")?(resource.equals("schedule")?pr:er):(resource.equals("schedule")?p:e),data,"name","draftConfigJson","lastError","snapshotJson");
            }
            for(String action:List.of("create","update","publish","availability"))verify(grants).current(Set.of("iqc:"+resource+":"+action));
        }
        verify(periodic).update("id",17,"name",null);verify(subscription).update("id",17,"name",null);assertEquals(before,json.writeValueAsString(List.of(p,e,pr,er)));verifyNoMoreInteractions(grants);
    }
    private List<MockHttpServletRequestBuilder> routes(){var requests=new ArrayList<MockHttpServletRequestBuilder>();for(String type:List.of("periodic-schedules","event-subscriptions")){String base="/api/iqc/"+type;requests.add(get(base));requests.add(get(base+"/id"));requests.add(get(base+"/id/versions"));requests.add(post(base).content("{}"));requests.add(put(base+"/id").content("{\"expectedRevision\":17}"));requests.add(post(base+"/id/publish").content("{\"expectedRevision\":17}"));requests.add(post(base+"/id/availability").content("{\"enabled\":true}"));}return requests;}
    @Test void revokedBaseGrantBlocksAllConfigurationReadsAndWrites()throws Exception{
        when(grants.current(anySet())).thenThrow(IqcException.accessDenied("revoked"));var mvc=mvc();for(var route:routes())mvc.perform(route.contentType(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());verifyNoInteractions(periodic,subscription);
    }
    @Test void legacyMissingWiringNeverFallsBackToRawConfiguration()throws Exception{
        var mvc=mvc(new PeriodicScheduleController(periodic),new EventSubscriptionController(subscription));for(var route:routes())mvc.perform(route.contentType(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(periodic,subscription,grants);
    }
    @Test void scopeRejectionFromOwningServicesIsPreserved()throws Exception{
        allow(true,true,true);when(periodic.get("id")).thenThrow(IqcException.accessDenied("scope"));when(subscription.get("id")).thenThrow(IqcException.accessDenied("scope"));var mvc=mvc();for(String type:List.of("periodic-schedules","event-subscriptions"))mvc.perform(get("/api/iqc/"+type+"/id")).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
    }
}

package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.selector.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Default HTTP projection with real sensitive-access decisions and isolated selection/asset domain. */
class SelectorPreviewHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ConversationSelectionService selections=mock(ConversationSelectionService.class);
    private final SelectorAssetService assets=mock(SelectorAssetService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private final String inline="/api/iqc/selectors/preview",published="/api/iqc/selectors/assets/asset/versions/7/preview";
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(
        new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
        new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new ConversationSelectorController(selections,assets,grants,projection),new SelectorAssetController(assets,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    private ConversationSelectionService.Preview source(){
        var date=LocalDateTime.of(2030,1,2,3,4,5);
        var distribution=new ConversationSelectionService.Distribution(Map.of("employee-a",2,"employee-b",3),Map.of("team",5),Map.of("department",5));
        return new ConversationSelectionService.Preview("preview-test",Instant.parse("2030-01-02T03:04:05Z"),new ConversationSelectionService.ResolvedWindow(SelectorDefinition.TimeField.STARTED,date,date.plusDays(1),"Asia/Shanghai","UTC"),11,9,7,5,true,distribution,distribution,List.of(new ConversationSelectionService.PreviewConversation("conversation","13812345678.txt","employee-a","team","department","13812345678",date,"HIGH")),"b".repeat(64));
    }
    private ObjectNode httpTree(Object source)throws Exception{return (ObjectNode)json.readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(source));}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void fullAndPiiAloneRevealIdentityWithoutChangingSelectionFacts(boolean full,boolean pii,boolean prompt)throws Exception{
        allow(full,pii,prompt);var source=source();String before=json.writeValueAsString(source);when(selections.preview(null,10)).thenReturn(source);when(assets.preview("asset",7,10)).thenReturn(source);var mvc=mvc();boolean reveal=full&&pii;
        for(String path:List.of(inline,published)){
            String body=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(jsonPath("$.code").value("000000")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString();
            var data=(ObjectNode)json.readTree(body).path("data");assertEquals(reveal,data.path("privacy").path("piiVisible").asBoolean());assertEquals(reveal,body.contains("employee-a"));assertEquals(reveal,body.contains("employee-b"));assertEquals(reveal,body.contains("13812345678"));
            for(String field:List.of("matchedDistribution","selectedDistribution")){
                if(reveal)assertEquals(httpTree(source).path(field).path("employees"),data.path(field).path("employees"));
                else assertTrue(data.path(field).path("employees").isNull(),"withheld employee distribution must remain unknown, not an empty map");
                assertEquals(httpTree(source).path(field).path("teams"),data.path(field).path("teams"));assertEquals(httpTree(source).path(field).path("departments"),data.path(field).path("departments"));
            }
            if(!reveal){assertTrue(data.path("preview").get(0).path("employeeId").isNull());assertTrue(data.path("privacy").path("withheldFields").toString().contains("matchedDistribution.employees"));}
            ObjectNode expected=httpTree(source),actual=data.deepCopy();expected.remove("privacy");actual.remove("privacy");
            for(String field:List.of("matchedDistribution","selectedDistribution")){((ObjectNode)expected.path(field)).remove("employees");((ObjectNode)actual.path(field)).remove("employees");}
            for(String field:List.of("employeeId","fileName","channel")){((ObjectNode)expected.path("preview").get(0)).remove(field);((ObjectNode)actual.path("preview").get(0)).remove(field);}
            assertEquals(expected,actual);
        }
        verify(grants).current(Set.of("iqc:selector:preview"));verify(grants).current(Set.of("iqc:selector:asset:version:preview"));verifyNoMoreInteractions(grants);assertEquals(before,json.writeValueAsString(source));
    }
    @Test void revokedGrantPreventsExactParsingAndBothQueries()throws Exception{
        when(grants.current(anySet())).thenThrow(IqcException.accessDenied("revoked"));var mvc=mvc();
        for(String path:List.of(inline,published))mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"definitionJson\":\"bad-private-input\"}")).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());verifyNoInteractions(selections,assets);
    }
    @Test void missingWiringCannotReturnRawPreview()throws Exception{
        var mvc=mvc(new ConversationSelectorController(selections),new SelectorAssetController(assets));for(String path:List.of(inline,published))mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(grants,selections,assets);
    }
    @Test void exactDefinitionStringAndExplicitPreviewSizeSurviveHttp()throws Exception{
        allow(false,false,false);String exact="{\"schemaVersion\":\"iqc-selector-v1\",\"maximumCount\":17,\"condition\":{\"field\":\"durationSeconds\",\"operator\":\"GT\",\"value\":9007199254740993}}";
        var definition=new SelectorDefinition(SelectorDefinition.SCHEMA,null,null,null,null,null,null,17);when(assets.requestDefinition(null,exact)).thenReturn(definition);when(selections.preview(definition,17)).thenReturn(source());
        mvc().perform(post(inline).contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("definitionJson",exact).put("previewSize",17).toString())).andExpect(jsonPath("$.code").value("000000"));
        var order=inOrder(grants,assets,selections);order.verify(grants).current(Set.of("iqc:selector:preview"));order.verify(assets).requestDefinition(null,exact);order.verify(selections).preview(definition,17);
    }
    @Test void domainScopeDenialIsPreserved()throws Exception{
        allow(true,true,false);when(selections.preview(null,10)).thenThrow(IqcException.accessDenied("scope"));when(assets.preview("asset",7,10)).thenThrow(IqcException.accessDenied("scope"));var mvc=mvc();for(String path:List.of(inline,published))mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void nullDistributionsRemainUnknownAndEmptyPreviewRemainsEmpty(){
        var value=source();var empty=new ConversationSelectionService.Preview(value.schemaVersion(),value.anchor(),value.resolvedWindow(),0,0,0,0,false,null,null,List.of(),value.definitionHash());
        var projected=projection.selectorPreview(empty,new SensitiveReadAccessService.Access("test",false,false,false,Instant.now()));assertNull(projected.matchedDistribution());assertNull(projected.selectedDistribution());assertTrue(projected.preview().isEmpty());assertEquals(value.definitionHash(),projected.definitionHash());
    }
    @Test void previewAuditNeverCopiesDefinitionsOrIdentityBodies()throws Exception{
        for(var method:List.of(ConversationSelectorController.class.getMethod("preview",ConversationSelectorController.PreviewRequest.class),SelectorAssetController.class.getMethod("preview",String.class,int.class,SelectorAssetController.Preview.class))){
            var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);assertFalse(audit.request());assertFalse(audit.response());var rate=method.getAnnotation(io.github.opensabre.governance.ratelimit.annotations.RateLimit.class);assertEquals(20,rate.maxCount());assertEquals(60,rate.period());
        }
    }
}

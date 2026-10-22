package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.SelectorAssetController;
import io.github.opensabre.iqc.selector.*;
import io.github.opensabre.iqc.selector.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real default MVC/projection; organization and asset persistence are isolated. */
class SelectorAssetHttpPrivacyTest {
    private static final String BASE="/api/iqc/selectors/assets";
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final SelectorAssetService assets=mock(SelectorAssetService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private final SelectorAsset row=new SelectorAsset();
    private final SelectorAssetVersion version=new SelectorAssetVersion();
    private SelectorAssetService.Prepared prepared;
    private MockMvc mvc(SelectorAssetController controller){return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new SelectorAssetController(assets,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    private void fixture()throws Exception{
        row.setId("asset");row.setCode("code");row.setName("13812345678");row.setDraftConfigJson("private-draft");row.setDraftRevision(17);row.setActivePublishedVersion(7);row.setStatus("INACTIVE");row.setOwnerGroupId("group");
        version.setId("revision");version.setSelectorId("asset");version.setVersionNo(7);version.setSourceDraftRevision(17);version.setSnapshotJson("private-snapshot");version.setContentHash("a".repeat(64));
        var condition=new SelectorDefinition.Condition(null,null,"durationSeconds",SelectorDefinition.Operator.GT,json.readTree("9007199254740993.123456789012345678"));
        var definition=new SelectorDefinition(SelectorDefinition.SCHEMA,condition,null,null,null,null,null,17);
        prepared=new SelectorAssetService.Prepared("asset",7,17,definition,version.getContentHash(),"group",null,null,false);
        when(assets.page(2,3)).thenReturn(new IqcPage<>(List.of(row),2,3,9));when(assets.get("asset")).thenReturn(row);when(assets.history("asset")).thenReturn(List.of(version));when(assets.viewVersion("asset",7)).thenReturn(prepared);
        when(assets.create("code","name",null)).thenReturn(row);when(assets.update("asset",17,"name",null)).thenReturn(row);when(assets.publish("asset",17)).thenReturn(version);when(assets.availability("asset",true)).thenReturn(row);
    }
    private com.fasterxml.jackson.databind.JsonNode response(MockMvc mvc,MockHttpServletRequestBuilder request)throws Exception{return json.readTree(mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value("000000")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString()).path("data");}
    private ObjectNode tree(Object value)throws Exception{return (ObjectNode)json.readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value));}
    private void metadata(Object source,com.fasterxml.jackson.databind.JsonNode result,String... remove)throws Exception{var expected=tree(source);var actual=(ObjectNode)result.deepCopy();expected.remove("privacy");actual.remove("privacy");for(String field:remove){expected.remove(field);actual.remove(field);}assertEquals(expected,actual);}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void assetBodyNeedsFullAndPiiButCatalogHistoryAndReceiptsNeverCarryIt(boolean full,boolean pii,boolean prompt)throws Exception{
        fixture();allow(full,pii,prompt);var mvc=mvc();String before=json.writeValueAsString(List.of(row,version,prepared));boolean reveal=full&&pii;
        var page=response(mvc,get(BASE+"?current=2&size=3"));assertEquals(9,page.path("total").asInt());assertEquals(2,page.path("current").asInt());assertEquals(3,page.path("size").asInt());assertFalse(page.toString().contains("private-draft"));metadata(row,page.path("records").get(0),"name","draftConfigJson");
        var detail=response(mvc,get(BASE+"/asset"));assertEquals(reveal,detail.toString().contains("private-draft"));assertEquals(reveal,detail.toString().contains("13812345678"));metadata(row,detail,"name","draftConfigJson");
        var history=response(mvc,get(BASE+"/asset/versions")).get(0);assertFalse(history.toString().contains("private-snapshot"));metadata(version,history,"snapshotJson");
        var exact=response(mvc,get(BASE+"/asset/versions/7"));assertEquals(reveal,exact.path("privacy").path("canonicalPayloadVisible").asBoolean());assertFalse(exact.path("availableForNewTasks").asBoolean());metadata(prepared,exact,"definition","definitionJson");
        if(reveal){assertEquals("9007199254740993.123456789012345678",exact.at("/definition/condition/value").asText());assertTrue(exact.path("definitionJson").asText().contains("9007199254740993.123456789012345678"));}else{assertTrue(exact.path("definition").isNull());assertTrue(exact.path("definitionJson").isMissingNode());}
        for(var request:List.of(post(BASE).content("{\"code\":\"code\",\"name\":\"name\"}"),put(BASE+"/asset").content("{\"expectedRevision\":17,\"name\":\"name\"}"),post(BASE+"/asset/publish").content("{\"expectedRevision\":17}"),post(BASE+"/asset/availability").content("{\"enabled\":true}"))){var receipt=response(mvc,request);assertFalse(receipt.toString().contains("private-"));assertFalse(receipt.path("privacy").path("canonicalPayloadVisible").asBoolean());metadata(receipt.has("versionNo")?version:row,receipt,"name","draftConfigJson","snapshotJson");}
        for(String suffix:List.of("view","detail:view","versions:view","version:view","create","update","publish","availability"))verify(grants).current(Set.of("iqc:selector:asset:"+suffix));verifyNoMoreInteractions(grants);assertEquals(before,json.writeValueAsString(List.of(row,version,prepared)));verify(assets,never()).published(anyString(),anyInt());
    }
    private List<MockHttpServletRequestBuilder> routes(){return List.of(get(BASE),get(BASE+"/asset"),get(BASE+"/asset/versions"),get(BASE+"/asset/versions/7"),post(BASE).content("{\"definitionJson\":\"unparsed\"}"),put(BASE+"/asset").content("{\"expectedRevision\":17,\"definitionJson\":\"unparsed\"}"),post(BASE+"/asset/publish").content("{\"expectedRevision\":17}"),post(BASE+"/asset/availability").content("{\"enabled\":true}"));}
    @Test void revokedBaseGrantPreventsAllParsingAndQueries()throws Exception{when(grants.current(anySet())).thenThrow(IqcException.accessDenied("revoked"));var mvc=mvc();for(var route:routes())mvc.perform(route.contentType(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value("IQC-1005"));verifyNoInteractions(assets);}
    @Test void legacyMissingComponentsFailClosed()throws Exception{var mvc=mvc(new SelectorAssetController(assets));for(var route:routes())mvc.perform(route.contentType(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(assets,grants);}
    @Test void exactConditionNumbersBindWithoutRoundingBeforeCreate()throws Exception{
        allow(false,false,false);when(assets.create(any(),any(),any())).thenReturn(row);when(assets.requestDefinition(any(),isNull())).thenAnswer(i->i.getArgument(0));
        response(mvc(),post(BASE).content("{\"code\":\"code\",\"name\":\"name\",\"definition\":{\"schemaVersion\":\"iqc-selector-v1\",\"maximumCount\":17,\"condition\":{\"field\":\"durationSeconds\",\"operator\":\"GT\",\"value\":9007199254740993.123456789012345678}}}"));
        var argument=org.mockito.ArgumentCaptor.forClass(SelectorDefinition.class);verify(assets).requestDefinition(argument.capture(),isNull());assertEquals("9007199254740993.123456789012345678",argument.getValue().condition().value().asText());
    }
    @Test void allAssetAuditDeclarationsExcludeRequestAndResponseBodies(){for(var method:SelectorAssetController.class.getDeclaredMethods()){var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);if(audit!=null){assertFalse(audit.request(),method.getName());assertFalse(audit.response(),method.getName());}}}
}

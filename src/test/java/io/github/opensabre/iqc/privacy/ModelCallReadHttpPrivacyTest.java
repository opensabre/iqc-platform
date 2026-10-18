package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.ModelGatewayController;
import io.github.opensabre.iqc.rest.TaskModelCallController;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Default MVC contracts with the real projection; organization and ledger queries are isolated. */
class ModelCallReadHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final GatewayCallService calls=mock(GatewayCallService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private final String global="/api/iqc/model-gateway/calls?modelKey=model&status=UNKNOWN&current=2&size=3";
    private final String task="/api/iqc/tasks/task/model-calls?executionId=run&status=UNKNOWN&current=2&size=3";
    private MockMvc mvc(Object... controllers){return MockMvcBuilders.standaloneSetup(controllers)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                    new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new ModelGatewayController(calls,grants,projection),new TaskModelCallController(calls,grants,projection));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    private GatewayCallService.CallPage source()throws Exception{
        // Fill every record component so a new or accidentally dropped scalar cannot pass unnoticed.
        var value=json.createObjectNode();
        for(var component:GatewayCallService.CallView.class.getRecordComponents()){
            if(component.getType()==String.class)value.put(component.getName(),component.getName()+"-value");
            else if(component.getType()==Long.class)value.put(component.getName(),9007199254740993L);
            else if(component.getType()==Integer.class)value.put(component.getName(),3);
            else if(component.getType()==LocalDateTime.class)value.put(component.getName(),"2030-01-02T03:04:05");
        }
        for(String field:List.of("capacityReleaseEvidence","budgetEvidenceRef"))value.put(field,"private-"+field);
        for(String field:List.of("responseModelId","requestedModelName","deploymentRef"))value.put(field,"Call 13812345678");
        value.put("calculatedCost","999999999999999999.123456789012345678");value.put("budgetReservedCost","0.123456789012345678");
        value.set("reconciliation",json.valueToTree(new GatewayCallService.ReconciliationView("call","model","9223372036854775807","0.123456789012345678","USD",null,"owner","user","private-reconciliation")));
        var row=json.treeToValue(value,GatewayCallService.CallView.class);
        var summary=new GatewayCallService.CostSummary("9007199254740993","2","9007199254740991",List.of(new GatewayCallService.CurrencyCost("USD","3","2","1","999999999999999999.123456789012345678")));
        return new GatewayCallService.CallPage(List.of(row),2,3,9,summary);
    }
    private ObjectNode httpTree(Object value)throws Exception{return (ObjectNode)json.readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value));}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void onlyThreeGrantsRevealEvidenceWhileEveryAccountingFactSurvives(boolean full,boolean pii,boolean prompt)throws Exception{
        allow(full,pii,prompt);var source=source();String original=json.writeValueAsString(source);
        when(calls.pageWithSummary("model","UNKNOWN",2,3)).thenReturn(source);when(calls.taskPageWithSummary("task","run","UNKNOWN",2,3)).thenReturn(source);
        var mvc=mvc();boolean reveal=full&&pii&&prompt;
        for(String path:List.of(global,task)){
            String body=mvc.perform(get(path)).andExpect(jsonPath("$.code").value("000000"))
                    .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.size").value(3)).andExpect(jsonPath("$.data.total").value(9))
                    .andReturn().getResponse().getContentAsString();
            var data=json.readTree(body).path("data");assertEquals(reveal,data.at("/privacy/evidenceVisible").asBoolean());
            assertEquals(reveal,body.contains("private-capacityReleaseEvidence"));assertEquals(reveal,body.contains("private-budgetEvidenceRef"));assertEquals(reveal,body.contains("private-reconciliation"));
            assertEquals(reveal,body.contains("13812345678"));
            ObjectNode expected=httpTree(source.records().get(0));ObjectNode actual=(ObjectNode)data.path("records").get(0).deepCopy();
            // Compare all remaining components exactly, including dates, IDs, hashes, versions and decimal strings.
            for(String field:List.of("capacityReleaseEvidence","budgetEvidenceRef","responseModelId","requestedModelName","deploymentRef")){expected.remove(field);actual.remove(field);}
            ((ObjectNode)expected.path("reconciliation")).remove("evidence");((ObjectNode)actual.path("reconciliation")).remove("evidence");
            assertEquals(expected,actual);assertEquals(httpTree(source.costSummary()),data.path("costSummary"));
        }
        assertSame(source.costSummary(),projection.gatewayCalls(source,grants.current(Set.of("test"))).costSummary());
        assertEquals(original,json.writeValueAsString(source));verify(grants).current(Set.of("iqc:model-gateway:calls:view"));verify(grants).current(Set.of("iqc:task:model-calls:view"));
        verify(grants,never()).current(Set.of("iqc:task:view"));
    }
    @Test void revokedCurrentGrantPreventsBothLedgerQueries()throws Exception{
        when(grants.current(anySet())).thenThrow(IqcException.accessDenied("revoked"));var mvc=mvc();
        for(String path:List.of(global,task))mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(calls);
    }
    @Test void missingWiringPreventsBothLedgerQueries()throws Exception{
        var mvc=mvc(new ModelGatewayController(calls),new TaskModelCallController(calls));
        for(String path:List.of(global,task))mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(calls,grants);
    }
    @Test void owningDomainScopeRejectionIsPreserved()throws Exception{
        allow(true,true,true);when(calls.pageWithSummary("model","UNKNOWN",2,3)).thenThrow(IqcException.accessDenied("not admin"));
        when(calls.taskPageWithSummary("task","run","UNKNOWN",2,3)).thenThrow(IqcException.accessDenied("task scope"));var mvc=mvc();
        for(String path:List.of(global,task))mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        var order=inOrder(grants,calls);order.verify(grants).current(Set.of("iqc:model-gateway:calls:view"));order.verify(calls).pageWithSummary("model","UNKNOWN",2,3);
        order.verify(grants).current(Set.of("iqc:task:model-calls:view"));order.verify(calls).taskPageWithSummary("task","run","UNKNOWN",2,3);
    }
    @Test void emptyLedgerAndAbsentReconciliationRemainAbsent()throws Exception{
        allow(false,false,false);var source=source();var access=grants.current(Set.of("test"));
        var row=(ObjectNode)json.valueToTree(source.records().get(0));row.putNull("reconciliation");
        var noReconciliation=new GatewayCallService.CallPage(List.of(json.treeToValue(row,GatewayCallService.CallView.class)),1,20,1,null);
        assertNull(projection.gatewayCalls(noReconciliation,access).records().get(0).reconciliation());
        var empty=new GatewayCallService.CallPage(List.of(),1,20,0,null);assertTrue(projection.gatewayCalls(empty,access).records().isEmpty());assertNull(projection.gatewayCalls(empty,access).costSummary());
    }
    @Test void readAuditDeclarationsNeverCopyLedgerBodies()throws Exception{
        var globalMethod=ModelGatewayController.class.getMethod("calls",String.class,String.class,long.class,long.class);
        var taskMethod=TaskModelCallController.class.getMethod("page",String.class,String.class,String.class,long.class,long.class);
        for(var method:List.of(globalMethod,taskMethod)){var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);assertFalse(audit.request());assertFalse(audit.response());}
    }
}

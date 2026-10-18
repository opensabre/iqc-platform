package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses default MVC converters; service mocks isolate exact binding from persistence and authorization. */
class ModelBudgetHttpContractTest {
    private final GatewayCallService calls=mock(GatewayCallService.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ModelGatewayController(calls))
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                    new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    private final ObjectMapper json=new ObjectMapper();
    private final String policy="{\"tokenLimit\":\"9223372036854775807\",\"costLimit\":\"999999999999999999.123456789012345678\",\"currency\":\"USD\",\"contextWindow\":100000000,\"deploymentRef\":\"provider:v1\",\"outputParameter\":\"MAX_TOKENS\",\"pricingHash\":\""+"a".repeat(64)+"\",\"evidenceRef\":\"evidence:1\",\"contractVerified\":true}";
    @Test void configureBindsIntegralLimitExactVersionAndWholeBudget()throws Exception{
        mvc.perform(put("/api/iqc/model-gateway/capacity/model").contentType("application/json")
                .content("{\"concurrencyLimit\":10000,\"expectedVersion\":\"9007199254740993\",\"budget\":"+policy+"}"))
                .andExpect(status().isOk());
        var budget=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(calls).configureCapacity(eq("model"),eq(10000),eq(9007199254740993L),budget.capture());
        assertEquals(json.readTree(policy),budget.getValue());
    }
    @Test void reconciliationKeepsLongTokensAndEighteenDecimalPlaces()throws Exception{
        mvc.perform(post("/api/iqc/model-gateway/calls/call/budget-reconciliation").contentType("application/json")
                .content("{\"modelKey\":\"model\",\"tokens\":\"9223372036854775807\",\"cost\":\"999999999999999999.123456789012345678\",\"currency\":\"USD\",\"evidence\":\"proof:1\",\"verified\":true}"))
                .andExpect(status().isOk());
        verify(calls).reconcileBudget("call","model",Long.MAX_VALUE,new BigDecimal("999999999999999999.123456789012345678"),"USD","proof:1",true);
    }
    @Test void recoveryForwardsCompletePolicyAndVersion()throws Exception{
        mvc.perform(post("/api/iqc/model-gateway/capacity/model/budget-recovery").contentType("application/json")
                .content("{\"expectedVersion\":\"9007199254740993\",\"budget\":"+policy+"}"))
                .andExpect(status().isOk());
        verify(calls).recoverBudget("model",9007199254740993L,json.readTree(policy));
    }
    @Test void unverifiedSupplierAndMissingAmountRemainExplicitServiceInputs()throws Exception{
        mvc.perform(post("/api/iqc/model-gateway/calls/call/budget-reconciliation").contentType("application/json")
                .content("{\"modelKey\":\"model\",\"tokens\":\"0\",\"cost\":null,\"verified\":false}"))
                .andExpect(status().isOk());
        verify(calls).reconcileBudget("call","model",0L,null,null,null,false);
    }
    @ParameterizedTest @ValueSource(strings={"null","1.0","1.5","\"1\"","true","2147483648","{}"})
    void invalidConcurrencyTypeOrIntOverflowStopsBeforeService(String value)throws Exception{
        mvc.perform(put("/api/iqc/model-gateway/capacity/model").contentType("application/json")
                .content("{\"concurrencyLimit\":"+value+",\"expectedVersion\":\"1\"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(calls);
    }
    @ParameterizedTest @ValueSource(strings={"-1","01","1.0","9223372036854775808","10000000000000000000",""})
    void invalidVersionStopsConfigureAndRecovery(String version)throws Exception{
        String encoded=json.writeValueAsString(version);
        mvc.perform(put("/api/iqc/model-gateway/capacity/model").contentType("application/json")
                .content("{\"concurrencyLimit\":1,\"expectedVersion\":"+encoded+"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));
        mvc.perform(post("/api/iqc/model-gateway/capacity/model/budget-recovery").contentType("application/json")
                .content("{\"expectedVersion\":"+encoded+",\"budget\":"+policy+"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(calls);
    }
    @ParameterizedTest @ValueSource(strings={"null","1","\"-1\"","\"01\"","\"1.0\"","\"9223372036854775808\"","\"10000000000000000000\""})
    void invalidTokenStringStopsBeforeReconciliation(String value)throws Exception{
        mvc.perform(post("/api/iqc/model-gateway/calls/call/budget-reconciliation").contentType("application/json")
                .content("{\"tokens\":"+value+",\"cost\":null}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(calls);
    }
    @ParameterizedTest @ValueSource(strings={"1.25","\"-1\"","\"1e3\"","\"01\"","\"1000000000000000000\"","\"0.1234567890123456789\""})
    void invalidAmountStopsBeforeReconciliation(String value)throws Exception{
        mvc.perform(post("/api/iqc/model-gateway/calls/call/budget-reconciliation").contentType("application/json")
                .content("{\"tokens\":\"1\",\"cost\":"+value+"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(calls);
    }
    @Test void nestedExactDecimalIsPreservedWithoutAllowingSecondDocument()throws Exception{
        String request="{\"concurrencyLimit\":1,\"expectedVersion\":\"1\",\"budget\":{\"precise\":0.123456789012345678}}";
        mvc.perform(put("/api/iqc/model-gateway/capacity/model").contentType("application/json").content(request)).andExpect(status().isOk());
        var budget=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(calls).configureCapacity(eq("model"),eq(1),eq(1L),budget.capture());
        assertEquals(new BigDecimal("0.123456789012345678"),budget.getValue().path("precise").decimalValue());
        clearInvocations(calls);
        mvc.perform(put("/api/iqc/model-gateway/capacity/model").contentType("application/json").content(request+" {}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("020000"))
                .andExpect(result->assertInstanceOf(org.springframework.http.converter.HttpMessageNotReadableException.class,result.getResolvedException()));
        verifyNoInteractions(calls);
    }
}

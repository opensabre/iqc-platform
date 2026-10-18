package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.TaskModelCallController;
import io.github.opensabre.iqc.result.llm.gateway.*;
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

/** Real MVC and sensitive-access decisions; organization grants and archive reads are isolated. */
class ModelDiagnosticReadHttpPrivacyTest {
    private static final String PATH="/api/iqc/tasks/task/model-calls/call/diagnostics";
    private static final Set<String> REQUIRED=Set.of("iqc:task:model-calls:view","iqc:task:model-call-data:view",SensitiveReadAccessService.PROMPT);
    private final GatewayCallService calls=mock(GatewayCallService.class);
    private final GatewayCallDataService data=mock(GatewayCallDataService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(new ObjectMapper().findAndRegisterModules());
    private MockMvc mvc(TaskModelCallController controller){return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                    new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();}
    private MockMvc mvc(){return mvc(new TaskModelCallController(calls,grants,projection,data));}
    private void allow(boolean full,boolean pii,boolean prompt){when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));}
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void everySensitiveGrantIsRequiredBeforeArchiveRead(boolean full,boolean pii,boolean prompt)throws Exception{
        allow(full,pii,prompt);
        String request="{\"message\":\"processed conversation 13812345678\"}";
        when(data.read("task","call")).thenReturn(new GatewayCallDataService.View("iqc-model-call-data-v1","call","task","run","CAPTURED",null,null,request,"private-response"));
        boolean allowed=full&&pii&&prompt;
        var result=mvc().perform(get(PATH)).andExpect(jsonPath("$.code").value(allowed?"000000":"IQC-1005"))
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        if(allowed){result.andExpect(jsonPath("$.data.requestJson").value(request)).andExpect(jsonPath("$.data.responseJson").value("private-response"));
            var order=inOrder(grants,data);order.verify(grants).current(REQUIRED);order.verify(data).read("task","call");
        }else{String body=result.andExpect(jsonPath("$.data").doesNotExist()).andReturn().getResponse().getContentAsString();assertFalse(body.contains("13812345678"));assertFalse(body.contains("private-response"));verifyNoInteractions(data);}
        verify(grants).current(REQUIRED);verifyNoInteractions(calls);verifyNoMoreInteractions(grants,data);
    }
    @Test void revokedBaseResourceIsRefusedBeforeArchiveRead()throws Exception{
        when(grants.current(REQUIRED)).thenThrow(IqcException.accessDenied("revoked"));
        mvc().perform(get(PATH)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verify(grants).current(REQUIRED);verifyNoInteractions(calls,data);
    }
    @Test void missingAuthorizationOrReaderComponentsFailClosed()throws Exception{
        mvc(new TaskModelCallController(calls)).perform(get(PATH)).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(grants,data,calls);
        allow(true,true,true);mvc(new TaskModelCallController(calls,grants,projection)).perform(get(PATH)).andExpect(jsonPath("$.code").value("IQC-1006"));verifyNoInteractions(data,calls);
    }
    @Test void owningTaskScopeDenialIsPreservedWithoutBody()throws Exception{
        allow(true,true,true);when(data.read("task","call")).thenThrow(IqcException.accessDenied("task scope"));
        mvc().perform(get(PATH)).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist()).andExpect(header().string("Cache-Control","private, no-store"));
        var order=inOrder(grants,data);order.verify(grants).current(REQUIRED);order.verify(data).read("task","call");verifyNoInteractions(calls);
    }
    @Test void independentPermissionRateLimitAndBodyFreeAuditAreDeclared()throws Exception{
        var method=TaskModelCallController.class.getMethod("diagnostics",String.class,String.class);
        assertEquals("iqc:task:model-call-data:view",method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class).code());
        var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);assertFalse(audit.request());assertFalse(audit.response());assertEquals("#id + ':' + #callId",audit.key());assertEquals("IQC_PRIVACY",audit.module());
        var rate=method.getAnnotation(io.github.opensabre.governance.ratelimit.annotations.RateLimit.class);assertEquals("iqc-model-call-data-read",rate.sceneCode());assertEquals(30,rate.maxCount());assertEquals(60,rate.period());
    }
}

package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import io.github.opensabre.governance.ratelimit.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Archive and serializer failures must not repeat suppliers or replace settlement facts. */
class ModelGatewayCallDataIsolationTest {
    private final GatewayCallService calls=mock(GatewayCallService.class);
    private final GatewayCallDataService data=mock(GatewayCallDataService.class);
    private final GovernanceRateLimiter limiter=mock(GovernanceRateLimiter.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters=mock(ObjectProvider.class);
    private final ModelGateway gateway=new ModelGateway(calls,limiter,new LlmQualityProperties(),meters,data);
    private final GatewayCallService.ModelIdentity model=new GatewayCallService.ModelIdentity("HTTP","https://supplier.example","model");
    private final GatewayCallService.Context context=new GatewayCallService.Context("record","rule","owner");
    private final GatewayCallService.Handle handle=new GatewayCallService.Handle("call",model.key());
    private final GatewayCallService.Usage usage=new GatewayCallService.Usage(3L,2L,5L,"model");
    @SuppressWarnings("unchecked") private final Supplier<String> supplier=mock(Supplier.class);
    @SuppressWarnings("unchecked") private final Supplier<String> request=mock(Supplier.class);
    @SuppressWarnings("unchecked") private final Function<String,String> response=mock(Function.class);
    @BeforeEach void setup(){var decision=mock(RateLimitDecision.class);when(decision.allowed()).thenReturn(true);when(limiter.check(any())).thenReturn(decision);
        when(calls.reserve(model,context,0)).thenReturn(handle);when(supplier.get()).thenReturn("answer");when(request.get()).thenReturn("request");when(response.apply("answer")).thenReturn("response");}
    @Test void modelIdentityHashUsesUtf8FramesAndCanonicalProviderEndpoint(){
        var first=new GatewayCallService.ModelIdentity("HTTP","https://SUPPLIER.example:443/","模型");
        var equivalent=new GatewayCallService.ModelIdentity("OPENAI","https://supplier.example","模型");
        assertEquals("a8b5e98ead9b76a031c669250d0d82d3f748b3b1e46f625b53c9bfb25819d8d7",first.key());assertEquals(first.key(),equivalent.key());
        assertNotEquals(first.key(),new GatewayCallService.ModelIdentity("HTTP","https://supplier.example","模型2").key());
    }
    @Test void disabledCaptureNeverSerializesOrStoresButStillSettles(){
        var result=gateway.invokeTracked(model,context,supplier,value->usage,request,response);assertEquals("answer",result.response());
        verifyNoInteractions(request,response);verify(data,never()).store(any(),any(),any());verify(calls).confirmed(handle,usage);verify(supplier).get();
    }
    @Test void enabledCaptureArchivesSameHandleAndSettlesOnce(){
        when(data.enabled()).thenReturn(true);gateway.invokeTracked(model,context,supplier,value->usage,request,response);
        verify(data).store(handle,"request","response");verify(calls).confirmed(handle,usage);verify(supplier).get();
    }
    @Test void archiveFailureCannotReplaceSuccessfulResponseOrRepeatSupplier(){
        when(data.enabled()).thenReturn(true);doThrow(new IllegalStateException("storage down")).when(data).store(any(),any(),any());
        assertEquals("answer",gateway.invokeTracked(model,context,supplier,value->usage,request,response).response());verify(calls).confirmed(handle,usage);verify(supplier).get();
    }
    @Test void serializerFailureStillSettlesWithoutRepeatingSupplier(){
        when(data.enabled()).thenReturn(true);when(request.get()).thenThrow(new IllegalStateException("request serialization"));when(response.apply("answer")).thenThrow(new IllegalStateException("response serialization"));
        assertEquals("answer",gateway.invokeTracked(model,context,supplier,value->usage,request,response).response());verify(calls).confirmed(handle,usage);verify(supplier).get();
    }
    @Test void supplierFailureRecordsUnknownAndRequestOnlyWithoutReplacingFailure(){
        when(data.enabled()).thenReturn(true);var failure=new IllegalStateException("supplier failed");when(supplier.get()).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->gateway.invokeTracked(model,context,supplier,value->usage,request,response)));
        verify(data).store(handle,"request",null);verify(calls).unknown(handle,failure);verify(calls,never()).confirmed(any(),any());verifyNoInteractions(response);verify(supplier).get();
    }
    @Test void failedSettlementNeverRetriesSupplierAfterArchive(){
        when(data.enabled()).thenReturn(true);doThrow(new IllegalStateException("settlement down")).when(calls).confirmed(handle,usage);
        assertThrows(GatewayAdmissionException.class,()->gateway.invokeTracked(model,context,supplier,value->usage,request,response));
        verify(data).store(handle,"request","response");verify(supplier).get();verify(calls).confirmed(handle,usage);
    }
}

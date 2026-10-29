package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.governance.ratelimit.*;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real ModelGateway invocation boundaries with controlled admission, ledger and supplier. */
class ModelGatewayActiveInvocationTest {
    final GatewayCallService calls=mock(GatewayCallService.class);
    final GovernanceRateLimiter limiter=mock(GovernanceRateLimiter.class);
    final GatewayCallDataService data=mock(GatewayCallDataService.class);
    final LlmQualityProperties properties=new LlmQualityProperties();
    final SimpleMeterRegistry meters=new SimpleMeterRegistry();
    final GatewayCallService.ModelIdentity model=new GatewayCallService.ModelIdentity("HTTP","https://supplier.example","model");
    final GatewayCallService.Context context=new GatewayCallService.Context("record","rule","owner");
    final GatewayCallService.Handle handle=new GatewayCallService.Handle("call",model.key());
    final GatewayCallService.Usage usage=new GatewayCallService.Usage(3L,2L,5L,"model");
    ModelGateway gateway;
    @BeforeEach void setup(){
        var allowed=mock(RateLimitDecision.class);when(allowed.allowed()).thenReturn(true);when(limiter.check(any())).thenReturn(allowed);
        when(calls.reserve(model,context,0)).thenReturn(handle);gateway=gateway(meters);
    }
    @AfterEach void closeRegistry(){meters.close();}
    @SuppressWarnings("unchecked") ModelGateway gateway(MeterRegistry registry){
        ObjectProvider<MeterRegistry> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(registry);
        return new ModelGateway(calls,limiter,properties,provider,data);
    }
    double active(){return meters.get("iqc.model.gateway.active").gauge().value();}
    @Test void concurrentSuppliersIncrementIndependentlyAndReturnToZero() throws Exception {
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var sequence=new AtomicInteger();when(calls.reserve(model,context,0)).thenAnswer(invocation->new GatewayCallService.Handle("call-"+sequence.incrementAndGet(),model.key()));
        Supplier<String> supplier=()->{started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}return "answer";};
        Runnable invoke=()->{try{gateway.invoke(model,context,supplier,value->usage);}catch(Throwable error){failure.set(error);}};
        var one=new Thread(invoke);var two=new Thread(invoke);one.start();two.start();
        try{assertTrue(started.await(3,TimeUnit.SECONDS));assertEquals(2,active());
            assertTrue(meters.get("iqc.model.gateway.active").gauge().getId().getTags().isEmpty());}
        finally{release.countDown();one.join(5000);two.join(5000);}
        assertFalse(one.isAlive());assertFalse(two.isAlive());assertNull(failure.get());assertEquals(0,active());
        verify(calls,times(2)).confirmed(any(),eq(usage));verify(calls,never()).unknown(any(),any());
        assertEquals(2,meters.get("iqc.model.gateway.invocation").tag("outcome","RESPONDED").timer().count());
    }
    @Test void nestedExplicitInvocationKeepsOuterCallActive(){
        String answer=gateway.invoke(model,context,()->{
            assertEquals(1,active());String inner=gateway.invoke(model,context,()->{assertEquals(2,active());return "inner";},value->usage);
            assertEquals(1,active());return inner;
        },value->usage);
        assertEquals("inner",answer);assertEquals(0,active());verify(calls,times(2)).confirmed(handle,usage);
    }
    @Test void admissionSerializationArchiveUsageAndSettlementAreOutsideActiveBoundary(){
        when(limiter.check(any())).thenAnswer(invocation->{assertEquals(0,active());var decision=mock(RateLimitDecision.class);when(decision.allowed()).thenReturn(true);return decision;});
        when(calls.reserve(model,context,0)).thenAnswer(invocation->{assertEquals(0,active());return handle;});
        when(data.enabled()).thenAnswer(invocation->{assertEquals(0,active());return true;});
        doAnswer(invocation->{assertEquals(0,active());return null;}).when(data).store(any(),any(),any());
        doAnswer(invocation->{assertEquals(0,active());return null;}).when(calls).confirmed(handle,usage);
        var answer=gateway.invokeTracked(model,context,()->{assertEquals(1,active());return "answer";},
                value->{assertEquals(0,active());return usage;},()->{assertEquals(0,active());return "request";},
                value->{assertEquals(0,active());return "response";});
        assertEquals("answer",answer.response());assertEquals(0,active());verify(data).store(handle,"request","response");verify(calls).confirmed(handle,usage);
    }
    @ParameterizedTest @ValueSource(strings={"configuration","rate","capacity"})
    void rejectedAdmissionDoesNotEnterSupplierOrCountActive(String phase){
        if(phase.equals("configuration"))properties.setRateLimitMaxCount(0);
        if(phase.equals("rate"))when(limiter.check(any())).thenReturn(null);
        if(phase.equals("capacity"))when(calls.reserve(model,context,0)).thenThrow(new GatewayAdmissionException("DENIED"));
        var invoked=new AtomicInteger();assertThrows(GatewayAdmissionException.class,()->gateway.invoke(model,context,()->{invoked.incrementAndGet();return "answer";},value->usage));
        assertEquals(0,invoked.get());assertEquals(0,active());assertNull(meters.find("iqc.model.gateway.invocation").timer());
        verify(calls,never()).confirmed(any(),any());verify(calls,never()).unknown(any(),any());
        if(!phase.equals("capacity"))verify(calls,never()).reserve(any(),any(),anyInt());
    }
    @Test void runtimeFailureIsRethrownAfterReturningActiveAndUnknownSettlement(){
        var failure=new IllegalStateException("supplier unavailable");var invoked=new AtomicInteger();
        doAnswer(invocation->{assertEquals(0,active());return null;}).when(calls).unknown(handle,failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->gateway.invoke(model,context,()->{invoked.incrementAndGet();assertEquals(1,active());throw failure;},value->usage)));
        assertEquals(0,active());assertEquals(1,invoked.get());verify(calls).unknown(handle,failure);verify(calls,never()).confirmed(any(),any());
        assertEquals(1,meters.get("iqc.model.gateway.invocation").tag("outcome","UNKNOWN").timer().count());
    }
    @Test void fatalErrorStillReturnsGaugeWithoutChangingExistingErrorSettlementSemantics(){
        var failure=new AssertionError("supplier fatal error");
        assertSame(failure,assertThrows(AssertionError.class,()->gateway.invoke(model,context,()->{assertEquals(1,active());throw failure;},value->usage)));
        assertEquals(0,active());verify(calls,never()).confirmed(any(),any());verify(calls,never()).unknown(any(),any());
        assertEquals(1,meters.get("iqc.model.gateway.invocation").tag("outcome","UNKNOWN").timer().count());
    }
    @Test void failedSettlementDoesNotKeepActiveOrRepeatSupplier(){
        var invoked=new AtomicInteger();doAnswer(invocation->{assertEquals(0,active());throw new IllegalStateException("ledger unavailable");}).when(calls).confirmed(handle,usage);
        assertThrows(GatewayAdmissionException.class,()->gateway.invoke(model,context,()->{invoked.incrementAndGet();return "answer";},value->usage));
        assertEquals(0,active());assertEquals(1,invoked.get());verify(calls).confirmed(handle,usage);verify(calls,never()).unknown(any(),any());
    }
    @Test void brokenRegistryCannotChangeSupplierOrLedgerBehavior(){
        // Use a fresh registry because an already registered Gauge bypasses registration filters.
        var broken=new SimpleMeterRegistry();try{
            broken.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter(){@Override public Meter.Id map(Meter.Id id){throw new IllegalStateException("broken registry");}});
            var gateway=gateway(broken);var invoked=new AtomicInteger();
            assertEquals("answer",gateway.invoke(model,context,()->{invoked.incrementAndGet();return "answer";},value->usage));
            assertEquals(1,invoked.get());verify(calls).confirmed(handle,usage);verify(calls,never()).unknown(any(),any());
        }finally{broken.close();}
    }
    @Test void originalConstructorStillExecutesAndSettlesWithoutRegistry(){
        var legacy=new ModelGateway(calls,limiter,properties);assertEquals("answer",legacy.invoke(model,context,()->"answer",value->usage));
        verify(calls).confirmed(handle,usage);assertEquals(0,active());
    }
}

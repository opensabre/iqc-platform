package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.governance.client.dto.RateLimitCheckRequest;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.ratelimit.enums.*;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.function.*;

/** One explicit model invocation. SDK/tool transport internals are a separate boundary. */
@Component @Slf4j
public class ModelGateway {
    private final GatewayCallService calls;
    private final GatewayCallDataService callData;
    private final GovernanceRateLimiter rateLimiter;
    private final LlmQualityProperties properties;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final java.util.concurrent.atomic.AtomicInteger activeInvocations=new java.util.concurrent.atomic.AtomicInteger();
    @org.springframework.beans.factory.annotation.Autowired
    public ModelGateway(GatewayCallService calls,GovernanceRateLimiter rateLimiter,LlmQualityProperties properties,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters,GatewayCallDataService callData){
        this.callData=callData;this.calls=calls;this.rateLimiter=rateLimiter;this.properties=properties;this.meters=meters.getIfAvailable();
        if(this.meters!=null)try{
            io.micrometer.core.instrument.Gauge.builder("iqc.model.gateway.active",activeInvocations,java.util.concurrent.atomic.AtomicInteger::get)
                    .description("Local explicit supplier invocations currently inside the invocation boundary; excludes admission and settlement")
                    .register(this.meters);
        }catch(RuntimeException unavailable){log.debug("Model active gauge unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    public ModelGateway(GatewayCallService calls,GovernanceRateLimiter rateLimiter,LlmQualityProperties properties,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters){this(calls,rateLimiter,properties,meters,null);}
    public ModelGateway(GatewayCallService calls,GovernanceRateLimiter rateLimiter,LlmQualityProperties properties){
        this.calls=calls;this.rateLimiter=rateLimiter;this.properties=properties;this.meters=null;this.callData=null;
    }
    private void recordInvocation(long started,String outcome){
        if(meters==null)return;
        try{io.micrometer.core.instrument.Timer.builder("iqc.model.gateway.invocation")
            .description("Explicit model supplier invocation duration; excludes admission and settlement")
            .tag("outcome",outcome).publishPercentileHistogram()
            .minimumExpectedValue(java.time.Duration.ofMillis(1)).maximumExpectedValue(java.time.Duration.ofMinutes(10))
            .register(meters).record(Math.max(0,System.nanoTime()-started),java.util.concurrent.TimeUnit.NANOSECONDS);}
        catch(RuntimeException unavailable){log.debug("Model invocation metric unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    private void metric(String name,double value,String... tags){
        if(meters==null)return;
        try{meters.counter(name,tags).increment(value);}
        catch(RuntimeException unavailable){log.debug("Model gateway metric unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    private void recordUsage(GatewayCallService.Usage usage){
        metric("iqc.model.gateway.usage",1,"status",usage.status());
        if("INVALID".equals(usage.status()))return;
        reportedTokens("input",usage.inputTokens());reportedTokens("output",usage.outputTokens());reportedTokens("total",usage.totalTokens());
        reportedTokens("cache_read_input",usage.cacheReadInputTokens());reportedTokens("cache_write_input",usage.cacheWriteInputTokens());
    }
    private void reportedTokens(String kind,Long value){if(value!=null)metric("iqc.model.gateway.tokens",value.doubleValue(),"kind",kind);}
    @Value("${iqc.llm.gateway.concurrency-limit:0}") private int concurrencyLimit;
    public void assertToolExecutionAllowed(GatewayCallService.Context context){
        try{calls.assertExecutionActive(context);}
        catch(GatewayAdmissionException denied){throw denied;}
        catch(RuntimeException unavailable){throw new GatewayAdmissionException("MODEL_TOOL_SOURCE_UNAVAILABLE");}
    }
    public record Invocation<T>(T response,GatewayCallService.Handle handle) { }
    public void markToolsStarted(GatewayCallService.Handle handle,GatewayCallService.Context context){
        try{calls.markToolsStarted(handle,context);}
        catch(GatewayAdmissionException denied){throw denied;}
        catch(RuntimeException unavailable){throw new GatewayAdmissionException("MODEL_TOOL_EXPOSURE_UNCONFIRMED");}
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public <T> T invoke(GatewayCallService.ModelIdentity model,GatewayCallService.Context context,Supplier<T> invoke,
                        Function<T,GatewayCallService.Usage> usage){return invokeTracked(model,context,invoke,usage).response();}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public <T> Invocation<T> invokeTracked(GatewayCallService.ModelIdentity model,GatewayCallService.Context context,Supplier<T> invoke,
                        Function<T,GatewayCallService.Usage> usage){
        return invokeTracked(model,context,invoke,usage,null,null);
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public <T> Invocation<T> invokeTracked(GatewayCallService.ModelIdentity model,GatewayCallService.Context context,Supplier<T> invoke,
            Function<T,GatewayCallService.Usage> usage,Supplier<String> requestData,Function<T,String> responseData){
        if(properties.getRateLimitMaxCount()<1 || properties.getRateLimitPeriod()<1){
            metric("iqc.model.gateway.admission",1,"stage","CONFIGURATION","outcome","DENIED");
            throw new GatewayAdmissionException("GATEWAY_RATE_CONFIGURATION_INVALID");}
        io.github.opensabre.governance.ratelimit.RateLimitDecision decision;
        try{decision=rateLimiter.check(RateLimitCheckRequest.builder().sceneCode("iqc-model-gateway-call").key(model.key()).keyPrefix("iqc:gateway")
                .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW).dimensions(List.of(RateLimitDimension.BUSINESS))
                .dimensionValues(Map.of(RateLimitDimension.BUSINESS,model.key())).maxCount(properties.getRateLimitMaxCount())
                .period(properties.getRateLimitPeriod()).enabled(true).build());}
        catch(RuntimeException unavailable){metric("iqc.model.gateway.admission",1,"stage","RATE","outcome","UNAVAILABLE");throw new GatewayAdmissionException("MODEL_RATE_ADMISSION_UNAVAILABLE");}
        if(decision==null || !decision.allowed()){metric("iqc.model.gateway.admission",1,"stage","RATE","outcome","DENIED");throw new GatewayAdmissionException("MODEL_RATE_ADMISSION_DENIED");}
        metric("iqc.model.gateway.admission",1,"stage","RATE","outcome","ACCEPTED");
        GatewayCallService.Handle handle;
        try{handle=calls.reserve(model,context,concurrencyLimit);}
        catch(GatewayAdmissionException denied){metric("iqc.model.gateway.admission",1,"stage","CAPACITY","outcome","DENIED");throw denied.withModelKey(model.key());}
        catch(RuntimeException unavailable){metric("iqc.model.gateway.admission",1,"stage","CAPACITY","outcome","UNAVAILABLE");throw new GatewayAdmissionException("MODEL_CAPACITY_ADMISSION_UNAVAILABLE");}
        metric("iqc.model.gateway.admission",1,"stage","CAPACITY","outcome","ACCEPTED");
        String archivedRequest=null;
        if(callData!=null && callData.enabled() && requestData!=null)try{archivedRequest=requestData.get();}
        catch(RuntimeException invalid){log.warn("Model diagnostic request unavailable type={}",invalid.getClass().getSimpleName());}
        final T response;long invocationStarted=System.nanoTime();String invocationOutcome="UNKNOWN";
        try{activeInvocations.incrementAndGet();
            try{response=invoke.get();invocationOutcome="RESPONDED";}
            finally{activeInvocations.decrementAndGet();recordInvocation(invocationStarted,invocationOutcome);}}
        catch(RuntimeException failure){archive(handle,archivedRequest,null);try{calls.unknown(handle,failure);metric("iqc.model.gateway.settlement",1,"outcome","UNKNOWN_RECORDED");}catch(RuntimeException appendFailure){
                metric("iqc.model.gateway.settlement",1,"outcome","UNKNOWN_UNCONFIRMED");
                log.warn("Model call remains unconfirmed call={} type={}",handle.callId(),appendFailure.getClass().getSimpleName());}
            throw failure;}
        String archivedResponse=null;
        if(callData!=null && callData.enabled() && responseData!=null)try{archivedResponse=responseData.apply(response);}
        catch(RuntimeException invalid){log.warn("Model diagnostic response unavailable type={}",invalid.getClass().getSimpleName());}
        archive(handle,archivedRequest,archivedResponse);
        GatewayCallService.Usage observed;
        try{observed=usage.apply(response);}catch(RuntimeException invalidUsage){observed=new GatewayCallService.Usage(-1L,null,null,null);}
        if(observed==null)observed=GatewayCallService.Usage.missing();
        // A failed settlement leaves RESERVED occupancy; never repeat a successful request to repair accounting.
        try{calls.confirmed(handle,observed);}catch(RuntimeException failure){
            metric("iqc.model.gateway.settlement",1,"outcome","RESPONSE_UNCONFIRMED");
            log.warn("Model response settlement unavailable call={} type={}",handle.callId(),failure.getClass().getSimpleName());
            throw new GatewayAdmissionException("MODEL_SETTLEMENT_UNCONFIRMED");}
        metric("iqc.model.gateway.settlement",1,"outcome","RESPONSE_CONFIRMED");recordUsage(observed);
        return new Invocation<>(response,handle);
    }
    private void archive(GatewayCallService.Handle handle,String request,String response){
        if(callData==null || !callData.enabled())return;
        try{callData.store(handle,request,response);}
        catch(RuntimeException unavailable){log.warn("Model diagnostic archive unavailable call={} type={}",handle.callId(),unavailable.getClass().getSimpleName());}
    }

}

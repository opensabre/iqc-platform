package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.governance.client.dto.RateLimitCheckRequest;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.ratelimit.enums.*;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.function.*;

/** One explicit model invocation. SDK/tool transport internals are a separate boundary. */
@Component @RequiredArgsConstructor @Slf4j
public class ModelGateway {
    private final GatewayCallService calls;
    private final GovernanceRateLimiter rateLimiter;
    private final LlmQualityProperties properties;
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
        if(properties.getRateLimitMaxCount()<1 || properties.getRateLimitPeriod()<1)
            throw new GatewayAdmissionException("GATEWAY_RATE_CONFIGURATION_INVALID");
        io.github.opensabre.governance.ratelimit.RateLimitDecision decision;
        try{decision=rateLimiter.check(RateLimitCheckRequest.builder().sceneCode("iqc-model-gateway-call").key(model.key()).keyPrefix("iqc:gateway")
                .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW).dimensions(List.of(RateLimitDimension.BUSINESS))
                .dimensionValues(Map.of(RateLimitDimension.BUSINESS,model.key())).maxCount(properties.getRateLimitMaxCount())
                .period(properties.getRateLimitPeriod()).enabled(true).build());}
        catch(RuntimeException unavailable){throw new GatewayAdmissionException("MODEL_RATE_ADMISSION_UNAVAILABLE");}
        if(decision==null || !decision.allowed())throw new GatewayAdmissionException("MODEL_RATE_ADMISSION_DENIED");
        GatewayCallService.Handle handle;
        try{handle=calls.reserve(model,context,concurrencyLimit);}
        catch(GatewayAdmissionException denied){throw denied;}
        catch(RuntimeException unavailable){throw new GatewayAdmissionException("MODEL_CAPACITY_ADMISSION_UNAVAILABLE");}
        final T response;
        try{response=invoke.get();}
        catch(RuntimeException failure){try{calls.unknown(handle,failure);}catch(RuntimeException appendFailure){
                log.warn("Model call remains unconfirmed call={} type={}",handle.callId(),appendFailure.getClass().getSimpleName());}
            throw failure;}
        GatewayCallService.Usage observed;
        try{observed=usage.apply(response);}catch(RuntimeException invalidUsage){observed=new GatewayCallService.Usage(-1L,null,null,null);}
        // A failed settlement leaves RESERVED occupancy; never repeat a successful request to repair accounting.
        try{calls.confirmed(handle,observed);}catch(RuntimeException failure){
            log.warn("Model response settlement unavailable call={} type={}",handle.callId(),failure.getClass().getSimpleName());
            throw new GatewayAdmissionException("MODEL_SETTLEMENT_UNCONFIRMED");}
        return new Invocation<>(response,handle);
    }
}

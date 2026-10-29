package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** Original frozen model billing, output and deployment declarations shared by inference transports. */
public final class FrozenModelGatewayMetadata {
    private FrozenModelGatewayMetadata() { }
    public static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.DeploymentDeclaration deployment(JsonNode profile){
        var window=profile.get("contextWindowTokens");var ref=profile.get("deploymentRef");
        if((window==null || window.isNull()) && (ref==null || ref.isNull()))return null;
        if(window!=null && !window.isNull() && (!window.isIntegralNumber() || !window.canConvertToInt()))
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_CONTEXT_WINDOW_INVALID");
        if(ref!=null && !ref.isNull() && !ref.isTextual())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_DEPLOYMENT_REFERENCE_INVALID");
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.DeploymentDeclaration(window==null || window.isNull()?null:window.intValue(),ref==null || ref.isNull()?null:ref.textValue());
    }
    public static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.OutputConstraint outputConstraint(JsonNode profile,boolean singleResponseScope){
        var cap=profile.get("maxOutputTokens");if(cap==null || cap.isNull())return null;
        if(!cap.isIntegralNumber() || !cap.canConvertToInt())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_LIMIT_INVALID");
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.OutputConstraint(cap.intValue(),profile.path("outputTokenParameter").asText(""),singleResponseScope);
    }
    public static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.PriceBinding price(JsonNode profile,boolean supported){
        if(!profile.path("id").isTextual() || !profile.path("versionNo").isIntegralNumber() || !profile.path("versionNo").canConvertToInt())return null;
        JsonNode value=profile.get("pricingJson");
        if(value!=null && !value.isNull() && !value.isTextual())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_PRICE_PROTOCOL_INVALID");
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.PriceBinding(profile.path("id").textValue(),profile.path("versionNo").intValue(),
                value==null || value.isNull()?null:value.textValue(),supported);
    }
}

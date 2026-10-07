package io.github.opensabre.iqc.result.llm.gateway;
/** Admission is a policy outcome, not a provider failure eligible for fallback. */
public final class GatewayAdmissionException extends RuntimeException {
    public GatewayAdmissionException(String code){super(code);}
}

package io.github.opensabre.iqc.result.llm.gateway;
/** Non-replayable gateway outcome; budget admission can pause before remote model I/O. */
public final class GatewayAdmissionException extends RuntimeException {
    private final String modelKey;
    public GatewayAdmissionException(String code){this(code,null);}
    public GatewayAdmissionException(String code,String modelKey){super(code);this.modelKey=modelKey;}
    public String modelKey(){return modelKey;}
    public GatewayAdmissionException withModelKey(String key){return modelKey==null?new GatewayAdmissionException(getMessage(),key):this;}
    public static GatewayAdmissionException budgetWait(Throwable failure){
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(int n=0;failure!=null && n<32 && seen.add(failure);n++,failure=failure.getCause())
            if(failure instanceof GatewayAdmissionException value && value.modelKey!=null && value.modelKey.matches("[a-f0-9]{64}")
                    && java.util.Set.of("MODEL_TOKEN_BUDGET_EXHAUSTED","MODEL_COST_BUDGET_EXHAUSTED","MODEL_BUDGET_QUARANTINED",
                        "MODEL_BUDGET_BOUND_UNVERIFIED","MODEL_COST_BUDGET_PRICE_UNVERIFIED").contains(value.getMessage()))return value;
        return null;
    }
}

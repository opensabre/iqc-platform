package io.github.opensabre.iqc.pipelineasset;

import java.math.BigDecimal;

/** Frozen configurable thresholds. Missing whole policy uses these explicit PRD defaults at publication. */
public record PublicationGatePolicy(String schemaVersion,BigDecimal minimumRiskRecall,BigDecimal minimumOverallF1,
        BigDecimal minimumRegressionPass,BigDecimal minimumStructuredSuccess,Long maximumP95Ms,Integer minimumSamples) {
    public static final String SCHEMA="iqc-publication-gate-v1";
    public PublicationGatePolicy {
        if(!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported publication gate schema");
        minimumRiskRecall=minimumRiskRecall==null?new BigDecimal("0.95"):minimumRiskRecall;
        minimumOverallF1=minimumOverallF1==null?new BigDecimal("0.92"):minimumOverallF1;
        minimumRegressionPass=minimumRegressionPass==null?BigDecimal.ONE:minimumRegressionPass;
        minimumStructuredSuccess=minimumStructuredSuccess==null?new BigDecimal("0.995"):minimumStructuredSuccess;
        maximumP95Ms=maximumP95Ms==null?5000L:maximumP95Ms;
        minimumSamples=minimumSamples==null?1:minimumSamples;
        for(var ratio:java.util.List.of(minimumRiskRecall,minimumOverallF1,minimumRegressionPass,minimumStructuredSuccess))
            if(ratio.compareTo(BigDecimal.ZERO)<0 || ratio.compareTo(BigDecimal.ONE)>0)
                throw new IllegalArgumentException("publication ratios must be between 0 and 1");
        if(maximumP95Ms<1 || maximumP95Ms>300000 || minimumSamples<1 || minimumSamples>1000)
            throw new IllegalArgumentException("invalid publication latency or sample bound");
    }
    public static PublicationGatePolicy defaults(){return new PublicationGatePolicy(SCHEMA,null,null,null,null,null,null);}
}

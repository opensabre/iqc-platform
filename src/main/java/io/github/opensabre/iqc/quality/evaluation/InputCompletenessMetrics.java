package io.github.opensabre.iqc.quality.evaluation;

import io.github.opensabre.iqc.conversation.InputCountManifest;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Describes frozen dataset inputs, independently of whether an execution produced an artifact. */
public final class InputCompletenessMetrics {
    private long samples,measured,invalid,complete,deficient,excess,expectedMessages,observedMessages,missingMessages,extraMessages;
    public record Summary(String schemaVersion,String scope,long expectedSamples,long measuredSamples,long unmeasuredSamples,
                          long invalidManifestSamples,long completeSamples,long deficientSamples,long excessSamples,
                          long expectedMessages,long observedMessages,long missingMessages,long extraMessages,
                          BigDecimal measurementCoverage,BigDecimal completeConversationRate,BigDecimal declaredMessageCoverage) { }
    public void add(GoldConversation gold){
        samples++;
        var context=gold.businessContext();
        var fields=context==null?null:context.path("conversation").path("businessContext").path("businessFields");
        var observation=InputCountManifest.observe(fields,gold.messages().size());
        switch(observation.state()){
            case UNMEASURED -> {return;}
            case INVALID -> {invalid++;return;}
            case COMPLETE -> complete++;
            case INCOMPLETE -> deficient++;
            case EXCESS -> excess++;
        }
        measured++;
        expectedMessages=Math.addExact(expectedMessages,observation.expectedMessages());
        observedMessages=Math.addExact(observedMessages,observation.observedMessages());
        missingMessages=Math.addExact(missingMessages,Math.max(0,observation.expectedMessages()-observation.observedMessages()));
        extraMessages=Math.addExact(extraMessages,Math.max(0,observation.observedMessages()-observation.expectedMessages()));
    }
    public Summary summary(){
        return new Summary("iqc-input-completeness-report-v1","FROZEN_REVIEWED_INPUT_UPSTREAM_DECLARED_MESSAGE_COUNTS",samples,
                measured,samples-measured,invalid,complete,deficient,excess,expectedMessages,observedMessages,missingMessages,extraMessages,
                ratio(measured,samples),ratio(complete,measured),ratio(expectedMessages-missingMessages,expectedMessages));
    }
    private static BigDecimal ratio(long numerator,long denominator){
        return denominator==0?null:BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator),6,RoundingMode.HALF_UP);
    }
}

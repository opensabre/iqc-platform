package io.github.opensabre.iqc.conversation;

import java.math.BigDecimal;

/** Optional offline segment alignment; unknown endpoints are never inferred from legacy wall-clock time. */
public final class TranscriptAlignment {
    private TranscriptAlignment() { }
    public static void validate(Long startMs,Long endMs,BigDecimal confidence) {
        if((startMs==null)!=(endMs==null) || startMs!=null && (startMs<0 || endMs<startMs || endMs>9007199254740991L))
            throw new IllegalArgumentException("Segment endpoints must be paired, ordered, nonnegative exact millisecond integers");
        if(confidence!=null && (confidence.signum()<0 || confidence.compareTo(BigDecimal.ONE)>0 || confidence.stripTrailingZeros().scale()>6))
            throw new IllegalArgumentException("Segment confidence must be between zero and one with at most six decimal places");
    }
}
